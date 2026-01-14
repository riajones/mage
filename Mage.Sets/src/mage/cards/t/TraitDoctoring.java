package mage.cards.t;

import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.target.TargetPermanent;

import java.util.UUID;

public final class TraitDoctoring extends CardImpl {
    public TraitDoctoring(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.SORCERY}, "{U}");

        // The spell targets but does not do anything
        this.getSpellAbility().addTarget(new TargetPermanent());
    }

    private TraitDoctoring(final TraitDoctoring card) {
        super(card);
    }

    @Override
    public TraitDoctoring copy() {
        return new TraitDoctoring(this);
    }
}
