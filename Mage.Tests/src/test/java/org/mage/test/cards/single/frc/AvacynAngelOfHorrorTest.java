package org.mage.test.cards.single.frc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * @author Riley Jones
 */
public class AvacynAngelOfHorrorTest extends CardTestPlayerBase {

    private static final String avacyn = "Avacyn, Angel of Horror";

    @Test
    public void testAvacynDiesAndReturns() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);
        addCard(Zone.BATTLEFIELD, playerA, avacyn);
        addCard(Zone.HAND, playerA, "Murder");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Murder", avacyn);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        execute();

        assertGraveyardCount(playerA, avacyn, 1);
        assertPermanentCount(playerA, avacyn, 0);

        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, avacyn, 0);
        assertPermanentCount(playerA, avacyn, 1);
    }

    @Test
    public void testAnotherNontokenCreatureDiesAndReturns() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);
        addCard(Zone.BATTLEFIELD, playerA, avacyn);
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion");
        addCard(Zone.HAND, playerA, "Murder");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Murder", "Silvercoat Lion");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, avacyn, 1);
        assertPermanentCount(playerA, "Silvercoat Lion", 1);
        assertGraveyardCount(playerA, "Silvercoat Lion", 0);
    }

    @Test
    public void testBothAvacynAndCreatureDieTogether() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 4);
        addCard(Zone.BATTLEFIELD, playerA, avacyn);
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion");
        addCard(Zone.HAND, playerA, "Damnation");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Damnation");
        setChoice(playerA, "Whenever {this} or another nontoken creature"); // trigger order
        setChoice(playerA, "At the beginning of the next end step, return that card"); // end step trigger order

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, avacyn, 1);
        assertPermanentCount(playerA, "Silvercoat Lion", 1);
        assertGraveyardCount(playerA, avacyn, 0);
        assertGraveyardCount(playerA, "Silvercoat Lion", 0);
    }

    @Test
    public void testTokenDoesNotReturn() {
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 2);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);
        addCard(Zone.BATTLEFIELD, playerA, avacyn);
        addCard(Zone.HAND, playerA, "Raise the Alarm");
        addCard(Zone.HAND, playerA, "Murder");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Raise the Alarm");
        castSpell(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Murder", "Soldier Token");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, avacyn, 1);
        assertPermanentCount(playerA, "Soldier Token", 1); // Only 1 soldier remains
    }

    @Test
    public void testOpponentCreatureDoesNotTrigger() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);
        addCard(Zone.BATTLEFIELD, playerA, avacyn);
        addCard(Zone.BATTLEFIELD, playerB, "Silvercoat Lion");
        addCard(Zone.HAND, playerA, "Murder");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Murder", "Silvercoat Lion");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, avacyn, 1);
        assertPermanentCount(playerA, "Silvercoat Lion", 0);
        assertPermanentCount(playerB, "Silvercoat Lion", 0);
        assertGraveyardCount(playerB, "Silvercoat Lion", 1);
    }

    @Test
    public void testStolenCreatureReturnsUnderYourControl() {
        // Player A steals Player B's creature with Act of Treason, then sacrifices it with Altar of Dementia
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 3);
        addCard(Zone.BATTLEFIELD, playerA, avacyn);
        addCard(Zone.BATTLEFIELD, playerA, "Altar of Dementia");
        addCard(Zone.BATTLEFIELD, playerB, "Silvercoat Lion");
        addCard(Zone.HAND, playerA, "Act of Treason");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Act of Treason", "Silvercoat Lion");
        activateAbility(1, PhaseStep.POSTCOMBAT_MAIN, playerA, "Sacrifice a creature: Target player mills", playerB);
        setChoice(playerA, "Silvercoat Lion");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, avacyn, 1);
        assertPermanentCount(playerA, "Silvercoat Lion", 1); // Returns under Player A's control!
        assertPermanentCount(playerB, "Silvercoat Lion", 0);
    }

    @Test
    public void testExiledFromGraveyardDoesNotReturn() {
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);
        addCard(Zone.BATTLEFIELD, playerA, avacyn);
        addCard(Zone.BATTLEFIELD, playerA, "Silvercoat Lion");
        addCard(Zone.HAND, playerA, "Murder");
        addCard(Zone.BATTLEFIELD, playerB, "Relic of Progenitus");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Murder", "Silvercoat Lion");
        activateAbility(1, PhaseStep.POSTCOMBAT_MAIN, playerB, "{T}: Target player exiles a card from their graveyard.", playerA);
        addTarget(playerA, "Silvercoat Lion");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, avacyn, 1);
        assertPermanentCount(playerA, "Silvercoat Lion", 0);
        assertExileCount(playerA, "Silvercoat Lion", 1);
    }
}
