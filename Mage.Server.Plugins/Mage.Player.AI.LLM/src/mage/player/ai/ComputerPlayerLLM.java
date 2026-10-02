package mage.player.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.ActivatedAbility.ActivationStatus;
import mage.abilities.PlayLandAbility;
import mage.abilities.SpellAbility;
import mage.cards.Card;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.players.PlayerImpl;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI: server side bot that asks an LLM (any OpenAI-compatible
 * /chat/completions endpoint, e.g. Ollama, vLLM, OpenAI) for its decisions.
 * <p>
 * The LLM decides which spells/abilities to play on main phase priorities and
 * which creatures to attack/block with. Target selection and mana payment are
 * handled by the inherited {@link ComputerPlayer} heuristics.
 * <p>
 * Configuration (system property, environment variable as fallback):
 * <ul>
 * <li>{@code mage.llm.enabled} / {@code MAGE_LLM_ENABLED} - default "true". Set to "false" to behave exactly like the base ComputerPlayer.</li>
 * <li>{@code mage.llm.base-url} / {@code MAGE_LLM_BASE_URL} - OpenAI-compatible base URL, default http://localhost:11434/v1 (Ollama). Requests go to {base}/chat/completions.</li>
 * <li>{@code mage.llm.api-key} / {@code MAGE_LLM_API_KEY} - default empty (sent as Bearer token when non-empty).</li>
 * <li>{@code mage.llm.model} / {@code MAGE_LLM_MODEL} - default "llama3".</li>
 * <li>{@code mage.llm.timeout-seconds} / {@code MAGE_LLM_TIMEOUT_SECONDS} - connect and read timeout, default 60.</li>
 * <li>{@code mage.llm.max-tokens} / {@code MAGE_LLM_MAX_TOKENS} - default 512.</li>
 * <li>{@code mage.llm.temperature} / {@code MAGE_LLM_TEMPERATURE} - default 0.2.</li>
 * </ul>
 * On any failure (endpoint down, timeout, malformed answer) the player falls
 * back to the inherited base AI behavior so a game can never stall because of
 * the LLM. LLM calls are never made inside game simulations.
 *
 * @author riley
 */
public class ComputerPlayerLLM extends ComputerPlayer {

    private static final Logger logger = Logger.getLogger(ComputerPlayerLLM.class);

    private static final int MAX_RULE_TEXT_LENGTH = 250;
    private static final int MAX_PERMANENTS_IN_PROMPT = 40;
    // safety valve: max LLM requests per turn so a pathological answer loop can't stall the game
    private static final int MAX_LLM_CALLS_PER_TURN = 10;

    // --- configuration (read once per call, cheap) ---

    private static String config(String sysProp, String envVar, String defaultValue) {
        String value = System.getProperty(sysProp);
        if (value == null || value.isEmpty()) {
            value = System.getenv(envVar);
        }
        return (value == null || value.isEmpty()) ? defaultValue : value.trim();
    }

    private static boolean isEnabled() {
        return !"false".equalsIgnoreCase(config("mage.llm.enabled", "MAGE_LLM_ENABLED", "true"));
    }

    private static String baseUrl() {
        return config("mage.llm.base-url", "MAGE_LLM_BASE_URL", "http://localhost:11434/v1");
    }

    private static String apiKey() {
        return config("mage.llm.api-key", "MAGE_LLM_API_KEY", "");
    }

    private static String model() {
        return config("mage.llm.model", "MAGE_LLM_MODEL", "llama3");
    }

    private static int timeoutSeconds() {
        try {
            return Math.max(1, Integer.parseInt(config("mage.llm.timeout-seconds", "MAGE_LLM_TIMEOUT_SECONDS", "60")));
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    private static int maxTokens() {
        try {
            return Math.max(16, Integer.parseInt(config("mage.llm.max-tokens", "MAGE_LLM_MAX_TOKENS", "512")));
        } catch (NumberFormatException e) {
            return 512;
        }
    }

    private static double temperature() {
        try {
            return Double.parseDouble(config("mage.llm.temperature", "MAGE_LLM_TEMPERATURE", "0.2"));
        } catch (NumberFormatException e) {
            return 0.2;
        }
    }

    // --- per-game state (copied by the copy constructor like ComputerPlayer6.actions) ---

    private int lastTurnNum = -1;
    private int llmCallsThisTurn = 0;
    private List<ActivatedAbility> pendingActions = new ArrayList<>();

    public ComputerPlayerLLM(String name, RangeOfInfluence range, int skill) {
        super(name, range);
    }

    protected ComputerPlayerLLM(final ComputerPlayerLLM player) {
        super(player);
        this.lastTurnNum = player.lastTurnNum;
        this.llmCallsThisTurn = player.llmCallsThisTurn;
        this.pendingActions = new ArrayList<>(player.pendingActions);
    }

    @Override
    public ComputerPlayerLLM copy() {
        return new ComputerPlayerLLM(this);
    }

    // ------------------------------------------------------------------
    // main decision entry point (engine calls it each time we have priority)
    // ------------------------------------------------------------------

    @Override
    public boolean priority(Game game) {
        if (!isEnabled() || game.isSimulation()) {
            super.priority(game);
            return false;
        }
        resetTurnStateIfNeeded(game);
        switch (game.getTurnStepType()) {
            case PRECOMBAT_MAIN:
            case POSTCOMBAT_MAIN:
                return mainPhase(game);
            default:
                pass(game);
                return false;
        }
    }

    private void resetTurnStateIfNeeded(Game game) {
        int turn = game.getTurnNum();
        if (turn != lastTurnNum) {
            lastTurnNum = turn;
            llmCallsThisTurn = 0;
        }
    }

    private boolean mainPhase(Game game) {
        if (pendingActions.isEmpty()) {
            List<ActivatedAbility> plan = buildPlan(game);
            if (plan.isEmpty()) {
                pass(game);
                return false;
            }
            if (llmCallsThisTurn >= MAX_LLM_CALLS_PER_TURN) {
                logger.warn("LLM player " + getName() + ": max LLM calls per turn reached, passing");
                pass(game);
                return false;
            }
            List<ActivatedAbility> chosen = askLlmForActions(game, plan);
            if (chosen == null) {
                // LLM unavailable or answered garbage: inherited behavior (do nothing now)
                pass(game);
                return false;
            }
            pendingActions = chosen;
        }

        // execute the chosen actions in order (like ComputerPlayer6.act)
        boolean acted = false;
        while (!pendingActions.isEmpty()) {
            ActivatedAbility ability = pendingActions.remove(0);
            logger.info(String.format("===> LLM selected action for %s: %s", getName(), describeAction(game, ability)));
            try {
                if (activateAbility(ability, game)) {
                    acted = true;
                }
            } catch (Exception e) {
                logger.error("LLM player " + getName() + ": action failed: " + ability.getRule(), e);
            }
        }
        if (!acted) {
            pass(game);
            return false;
        }
        // do not pass yet: the next priority in this step may allow more actions
        // (e.g. played a land, now can cast a spell). We pass when nothing is left to do.
        return true;
    }

    /**
     * Enumerate everything we are legally able to do right now (no LLM involved).
     */
    private List<ActivatedAbility> buildPlan(Game game) {
        List<ActivatedAbility> plan = new ArrayList<>();
        // castable spells from hand
        for (UUID cardId : getHand()) {
            Card card = game.getCard(cardId);
            if (card == null) {
                continue;
            }
            Map<UUID, SpellAbility> castable = PlayerImpl.getCastableSpellAbilities(game, playerId, card, Zone.HAND, false);
            for (SpellAbility spellAbility : castable.values()) {
                plan.add(spellAbility);
                break; // one spell ability per card is enough
            }
        }
        // playable lands
        if (canPlayLand()) {
            for (UUID cardId : getHand()) {
                Card card = game.getCard(cardId);
                if (card == null || !card.isLand()) {
                    continue;
                }
                for (Ability ability : card.getAbilities(game)) {
                    if (ability instanceof PlayLandAbility) {
                        plan.add((ActivatedAbility) ability);
                        break;
                    }
                }
            }
        }
        // activatable non-mana abilities on our permanents
        for (Permanent permanent : game.getBattlefield().getAllActivePermanents(playerId)) {
            for (Ability ability : permanent.getAbilities(game)) {
                if (!(ability instanceof ActivatedAbility) || ability.isManaAbility()) {
                    continue;
                }
                ActivatedAbility activated = (ActivatedAbility) ability;
                ActivationStatus status = activated.canActivate(playerId, game);
                if (status != null && status.canActivate()) {
                    plan.add(activated);
                }
            }
        }
        return plan;
    }
    // ------------------------------------------------------------------
    // combat decisions
    // ------------------------------------------------------------------

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        if (!isEnabled() || game.isSimulation() || !playerId.equals(attackingPlayerId)) {
            super.selectAttackers(game, attackingPlayerId);
            return;
        }
        resetTurnStateIfNeeded(game);

        // collect attackable creatures grouped by defender
        List<Permanent> attackers = new ArrayList<>();
        List<UUID> attackerDefenders = new ArrayList<>();
        for (UUID defenderId : game.getOpponents(playerId, true)) {
            List<Permanent> available = getAvailableAttackers(defenderId, game);
            for (Permanent permanent : available) {
                attackers.add(permanent);
                attackerDefenders.add(defenderId);
            }
        }
        if (attackers.isEmpty()) {
            return;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Combat step: declare attackers. Choose which of your creatures attack.\n");
        prompt.append("Your creatures that can attack:\n");
        for (int i = 0; i < attackers.size(); i++) {
            Permanent permanent = attackers.get(i);
            String defenderName = game.getPlayer(attackerDefenders.get(i)) == null ? "?" : plain(game.getPlayer(attackerDefenders.get(i)).getLogName());
            prompt.append('[').append(i).append("] ").append(plain(permanent.getLogName()))
                    .append(" - ").append(powerToughness(permanent))
                    .append(" (attacking ").append(defenderName).append(")\n");
        }
        appendOpponentBattlefields(game, prompt);
        prompt.append("\nRespond with ONLY a JSON object: {\"attackers\":[<index>,...]} - empty list [] to not attack.");

        List<Integer> chosen = askLlmForIndices(prompt.toString(), "attackers", attackers.size());
        if (chosen == null) {
            return; // fallback: inherited behavior (no attack)
        }
        for (Integer index : chosen) {
            if (index == null || index < 0 || index >= attackers.size()) {
                continue;
            }
            Permanent permanent = attackers.get(index);
            UUID defenderId = attackerDefenders.get(index);
            try {
                declareAttacker(permanent.getId(), defenderId, game, true);
            } catch (Exception e) {
                logger.error("LLM player " + getName() + ": declare attacker failed for " + permanent.getLogName(), e);
            }
        }
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        if (!isEnabled() || game.isSimulation() || !playerId.equals(defendingPlayerId)) {
            super.selectBlockers(source, game, defendingPlayerId);
            return;
        }
        resetTurnStateIfNeeded(game);

        List<Permanent> attackers = new ArrayList<>();
        for (UUID attackerId : game.getCombat().getAttackers()) {
            Permanent permanent = game.getPermanent(attackerId);
            if (permanent != null) {
                attackers.add(permanent);
            }
        }
        if (attackers.isEmpty()) {
            return;
        }

        // blockers that can block at least one attacker
        List<Permanent> blockers = new ArrayList<>();
        for (Permanent blocker : getAvailableBlockers(game)) {
            for (Permanent attacker : attackers) {
                if (blocker.canBlock(attacker.getId(), game)) {
                    blockers.add(blocker);
                    break;
                }
            }
        }
        if (blockers.isEmpty()) {
            return;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Combat step: declare blockers. Opponent's creatures are attacking you.\n");
        prompt.append("Attackers:\n");
        for (int i = 0; i < attackers.size(); i++) {
            Permanent attacker = attackers.get(i);
            prompt.append('[').append(i).append("] ").append(plain(attacker.getLogName()))
                    .append(" - ").append(powerToughness(attacker)).append('\n');
        }
        prompt.append("Your creatures that can block:\n");
        for (int i = 0; i < blockers.size(); i++) {
            Permanent blocker = blockers.get(i);
            StringBuilder canBlock = new StringBuilder();
            for (int j = 0; j < attackers.size(); j++) {
                if (blocker.canBlock(attackers.get(j).getId(), game)) {
                    if (canBlock.length() > 0) {
                        canBlock.append(',');
                    }
                    canBlock.append('a').append(j);
                }
            }
            prompt.append('[').append(i).append("] ").append(plain(blocker.getLogName()))
                    .append(" - ").append(powerToughness(blocker))
                    .append(" (can block: ").append(canBlock).append(")\n");
        }
        prompt.append("\nRespond with ONLY a JSON object: {\"blocks\":[{\"blocker\":<b-index>,\"attacker\":<a-index>},...]} - empty list [] to not block.");

        List<int[]> chosen = askLlmForBlocks(prompt.toString(), attackers.size(), blockers.size());
        if (chosen == null) {
            return; // fallback: inherited behavior (no block)
        }
        for (int[] pair : chosen) {
            int blockerIndex = pair[0];
            int attackerIndex = pair[1];
            if (blockerIndex < 0 || blockerIndex >= blockers.size() || attackerIndex < 0 || attackerIndex >= attackers.size()) {
                continue;
            }
            Permanent blocker = blockers.get(blockerIndex);
            Permanent attacker = attackers.get(attackerIndex);
            if (!blocker.canBlock(attacker.getId(), game)) {
                continue;
            }
            try {
                declareBlocker(playerId, blocker.getId(), attacker.getId(), game);
            } catch (Exception e) {
                logger.error("LLM player " + getName() + ": declare blocker failed for " + blocker.getLogName(), e);
            }
        }
    }

    // ------------------------------------------------------------------
    // LLM request / parsing
    // ------------------------------------------------------------------

    private static final String SYSTEM_PROMPT =
            "You are an expert Magic: The Gathering player. You receive a compact game state and the legal actions you may take right now.\n"
            + "Choose the best action(s) for the current moment. Actions execute in the order you list them, before anything on the stack resolves.\n"
            + "Use your mana efficiently, protect your life total and board advantage, and don't play spells you cannot pay for.\n"
            + "Respond with ONLY a JSON object - no markdown, no code fences, no commentary.";

    /**
     * Ask the LLM which of the given actions to take.
     *
     * @return chosen abilities in execution order, or null if the LLM call failed (caller must fall back)
     */
    private List<ActivatedAbility> askLlmForActions(Game game, List<ActivatedAbility> plan) {
        StringBuilder prompt = new StringBuilder();
        appendGameState(game, prompt);
        prompt.append("\nLegal actions you can take right now:\n");
        for (int i = 0; i < plan.size(); i++) {
            prompt.append('[').append(i).append("] ").append(describeAction(game, plan.get(i))).append('\n');
        }
        prompt.append("\nRespond with ONLY a JSON object: {\"actions\":[<index>,...]} - indices in execution order, empty list [] to pass priority.");

        List<Integer> indices = askLlmForIndices(prompt.toString(), "actions", plan.size());
        if (indices == null) {
            return null;
        }
        List<ActivatedAbility> chosen = new ArrayList<>();
        for (Integer index : indices) {
            if (index != null && index >= 0 && index < plan.size()) {
                chosen.add(plan.get(index));
            }
        }
        return chosen;
    }

    /**
     * Generic LLM call returning a list of integer indices from the named JSON array field.
     *
     * @return parsed indices (possibly empty), or null on any failure
     */
    private List<Integer> askLlmForIndices(String userPrompt, String fieldName, int maxIndex) {
        String content = chatCompletion(userPrompt);
        if (content == null) {
            return null;
        }
        try {
            JsonObject root = parseJsonObject(content);
            JsonElement field = root.get(fieldName);
            if (field == null || !field.isJsonArray()) {
                logger.warn("LLM player " + getName() + ": answer has no '" + fieldName + "' array: " + truncate(content, 300));
                return null;
            }
            List<Integer> indices = new ArrayList<>();
            for (JsonElement element : field.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                    int value = element.getAsInt();
                    if (value >= 0 && value < maxIndex) {
                        indices.add(value);
                    }
                }
            }
            return indices;
        } catch (Exception e) {
            logger.warn("LLM player " + getName() + ": can't parse answer: " + truncate(content, 300), e);
            return null;
        }
    }

    /**
     * LLM call for blocker assignments: returns [blockerIndex, attackerIndex] pairs or null on failure.
     */
    private List<int[]> askLlmForBlocks(String userPrompt, int maxAttacker, int maxBlocker) {
        String content = chatCompletion(userPrompt);
        if (content == null) {
            return null;
        }
        try {
            JsonObject root = parseJsonObject(content);
            JsonElement field = root.get("blocks");
            if (field == null || !field.isJsonArray()) {
                logger.warn("LLM player " + getName() + ": answer has no 'blocks' array: " + truncate(content, 300));
                return null;
            }
            List<int[]> pairs = new ArrayList<>();
            for (JsonElement element : field.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject pair = element.getAsJsonObject();
                JsonElement blockerElement = pair.get("blocker");
                JsonElement attackerElement = pair.get("attacker");
                if (blockerElement == null || attackerElement == null
                        || !blockerElement.isJsonPrimitive() || !attackerElement.isJsonPrimitive()) {
                    continue;
                }
                int blockerIndex = blockerElement.getAsInt();
                int attackerIndex = attackerElement.getAsInt();
                if (blockerIndex >= 0 && blockerIndex < maxBlocker && attackerIndex >= 0 && attackerIndex < maxAttacker) {
                    pairs.add(new int[]{blockerIndex, attackerIndex});
                }
            }
            return pairs;
        } catch (Exception e) {
            logger.warn("LLM player " + getName() + ": can't parse blocks answer: " + truncate(content, 300), e);
            return null;
        }
    }

    private static JsonObject parseJsonObject(String content) {
        String cleaned = content.trim();
        // strip markdown code fences if the model added them anyway
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int lastFence = cleaned.lastIndexOf("```");
            if (lastFence >= 0) {
                cleaned = cleaned.substring(0, lastFence);
            }
        }
        // be tolerant of extra text around the JSON object
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        return JsonParser.parseString(cleaned).getAsJsonObject();
    }

    /**
     * POST to the OpenAI-compatible /chat/completions endpoint.
     *
     * @return assistant message content, or null on any failure
     */
    private String chatCompletion(String userPrompt) {
        llmCallsThisTurn++;
        long start = System.currentTimeMillis();
        try {
            String url = baseUrl();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(url + "/chat/completions").openConnection();
            int timeoutMs = timeoutSeconds() * 1000;
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("Content-Type", "application/json");
            String key = apiKey();
            if (!key.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + key);
            }
            connection.setDoOutput(true);

            JsonObject body = new JsonObject();
            body.addProperty("model", model());
            body.addProperty("stream", false);
            body.addProperty("temperature", temperature());
            body.addProperty("max_tokens", maxTokens());
            JsonArray messages = new JsonArray();
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", SYSTEM_PROMPT);
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", userPrompt);
            messages.add(systemMessage);
            messages.add(userMessage);
            body.add("messages", messages);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream outputStream = connection.getOutputStream();
            try {
                outputStream.write(payload);
            } finally {
                outputStream.close();
            }

            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = readAll(stream);
            if (statusCode != 200) {
                logger.warn("LLM player " + getName() + ": endpoint returned HTTP " + statusCode + ": " + truncate(response, 300));
                return null;
            }
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.size() != 1) {
                logger.warn("LLM player " + getName() + ": unexpected answer format: " + truncate(response, 300));
                return null;
            }
            String content = choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
            logger.info(String.format("LLM player %s: model=%s answered in %d ms", getName(), model(), System.currentTimeMillis() - start));
            return content;
        } catch (Exception e) {
            logger.warn("LLM player " + getName() + ": LLM call failed (" + baseUrl() + ", model=" + model() + "): " + e.getMessage());
            return null;
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                result.append(buffer, 0, read);
            }
        } finally {
            reader.close();
        }
        return result.toString();
    }

    // ------------------------------------------------------------------
    // prompt building helpers
    // ------------------------------------------------------------------

    private void appendGameState(Game game, StringBuilder prompt) {
        prompt.append("Turn ").append(game.getTurnNum()).append(". You are \"").append(plain(getLogName())).append("\".\n");
        prompt.append("Your life: ").append(getLife()).append('\n');
        for (UUID opponentId : game.getOpponents(playerId, true)) {
            if (game.getPlayer(opponentId) != null) {
                prompt.append(plain(game.getPlayer(opponentId).getLogName())).append(" life: ").append(game.getPlayer(opponentId).getLife()).append('\n');
            }
        }
        // stack (top first)
        StringBuilder stack = new StringBuilder();
        for (StackObject object : game.getStack()) {
            if (stack.length() > 0) {
                stack.append(" | ");
            }
            stack.append(truncate(plain(object.toString()), 100));
        }
        prompt.append("Stack: ").append(stack.length() == 0 ? "<empty>" : stack).append('\n');

        // our hand
        prompt.append("Your hand:\n");
        int index = 0;
        for (UUID cardId : getHand()) {
            Card card = game.getCard(cardId);
            if (card == null) {
                continue;
            }
            prompt.append('[').append(index++).append("] ").append(plain(card.getLogName())).append(" - ")
                    .append(truncate(joinRules(card), MAX_RULE_TEXT_LENGTH)).append('\n');
        }
        appendBattlefield(game, playerId, "Your battlefield", prompt);
        for (UUID opponentId : game.getOpponents(playerId, true)) {
            if (game.getPlayer(opponentId) != null) {
                appendBattlefield(game, opponentId, plain(game.getPlayer(opponentId).getLogName()) + "'s battlefield", prompt);
            }
        }
    }

    private void appendOpponentBattlefields(Game game, StringBuilder prompt) {
        for (UUID opponentId : game.getOpponents(playerId, true)) {
            if (game.getPlayer(opponentId) != null) {
                appendBattlefield(game, opponentId, plain(game.getPlayer(opponentId).getLogName()) + "'s battlefield", prompt);
            }
        }
    }

    private void appendBattlefield(Game game, UUID controllerId, String title, StringBuilder prompt) {
        Collection<Permanent> permanents = game.getBattlefield().getAllActivePermanents(controllerId);
        if (permanents.isEmpty()) {
            return;
        }
        prompt.append(title).append(":\n");
        int count = 0;
        for (Permanent permanent : permanents) {
            if (count++ >= MAX_PERMANENTS_IN_PROMPT) {
                prompt.append("... (more)\n");
                break;
            }
            StringBuilder line = new StringBuilder();
            line.append(plain(permanent.getLogName()));
            if (permanent.getPower().getValue() >= 0 || permanent.getToughness().getValue() >= 0) {
                line.append(" - ").append(powerToughness(permanent));
            }
            line.append(" - ").append(permanent.isTapped() ? "tapped" : "untapped");
            String rules = truncate(joinRules(permanent), MAX_RULE_TEXT_LENGTH);
            if (!rules.isEmpty()) {
                line.append(" - ").append(rules);
            }
            prompt.append(line).append('\n');
        }
    }

    private static String powerToughness(Permanent permanent) {
        return permanent.getPower().getValue() + "/" + permanent.getToughness().getValue();
    }

    private static String joinRules(Card card) {
        List<String> rules = card.getRules();
        if (rules == null || rules.isEmpty()) {
            return "";
        }
        return String.join(" ", rules);
    }

    private String describeAction(Game game, ActivatedAbility ability) {
        Card sourceCard = ability.getSourceId() == null ? null : game.getCard(ability.getSourceId());
        if (ability instanceof PlayLandAbility) {
            return "Play land " + (sourceCard == null ? "?" : plain(sourceCard.getLogName()));
        }
        if (ability instanceof SpellAbility) {
            return "Cast " + (sourceCard == null ? "?" : plain(sourceCard.getLogName())) + " (" + truncate(plain(ability.getRule()), 120) + ")";
        }
        return "Activate " + (sourceCard == null ? "?" : plain(sourceCard.getLogName())) + ": " + truncate(plain(ability.getRule()), 160);
    }

    /**
     * Strip HTML markup from xmage log names / rule text for cleaner LLM prompts.
     */
    private static String plain(String value) {
        if (value == null) {
            return "";
        }
        int firstTag = value.indexOf('<');
        if (firstTag < 0) {
            return value;
        }
        StringBuilder result = new StringBuilder(value.length());
        result.append(value, 0, firstTag);
        boolean inTag = false;
        for (int i = firstTag; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
            } else if (!inTag) {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
