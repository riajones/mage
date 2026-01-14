package mage.cards.m;

import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.target.TargetPermanent;

import java.util.UUID;

public final class MindBend extends CardImpl {
    public MindBend(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.INSTANT}, "{U}");

        // The spell targets but does not do anything
        this.getSpellAbility().addTarget(new TargetPermanent());
    }

    private MindBend(final MindBend card) {
        super(card);
    }

    @Override
    public MindBend copy() {
        return new MindBend(this);
    }
}
