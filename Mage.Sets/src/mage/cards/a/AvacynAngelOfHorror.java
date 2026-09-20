package mage.cards.a;

import mage.MageInt;
import mage.abilities.common.DiesCreatureTriggeredAbility;
import mage.abilities.common.delayed.AtTheBeginOfNextEndStepDelayedTriggeredAbility;
import mage.abilities.effects.common.CreateDelayedTriggeredAbilityEffect;
import mage.abilities.effects.common.ReturnToBattlefieldUnderYourControlTargetEffect;
import mage.abilities.keyword.DeathtouchAbility;
import mage.abilities.keyword.FlyingAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.StaticFilters;

import java.util.UUID;

/**
 * @author Riley Jones
 */
public final class AvacynAngelOfHorror extends CardImpl {

    public AvacynAngelOfHorror(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{5}{B}{B}{B}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.ANGEL);
        this.power = new MageInt(8);
        this.toughness = new MageInt(8);

        // Flying
        this.addAbility(FlyingAbility.getInstance());

        // Deathtouch
        this.addAbility(DeathtouchAbility.getInstance());

        // Whenever Avacyn or another nontoken creature you control dies, return that card to the battlefield under your control at the beginning of the next end step.
        this.addAbility(new DiesCreatureTriggeredAbility(
                new CreateDelayedTriggeredAbilityEffect(
                        new AtTheBeginOfNextEndStepDelayedTriggeredAbility(
                                new ReturnToBattlefieldUnderYourControlTargetEffect()
                        )
                ).setText("return that card to the battlefield under your control at the beginning of the next end step"),
                false, StaticFilters.FILTER_CONTROLLED_CREATURE_NON_TOKEN, true
        ).setTriggerPhrase("Whenever {this} or another nontoken creature you control dies, "));
    }

    private AvacynAngelOfHorror(final AvacynAngelOfHorror card) {
        super(card);
    }

    @Override
    public AvacynAngelOfHorror copy() {
        return new AvacynAngelOfHorror(this);
    }
}
