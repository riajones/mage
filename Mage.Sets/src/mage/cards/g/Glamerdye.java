package mage.cards.g;

import mage.abilities.keyword.RetraceAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.target.TargetPermanent;

import java.util.UUID;

public final class Glamerdye extends CardImpl {
    public Glamerdye(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.SORCERY}, "{U}");

        // Retrace
        this.addAbility(new RetraceAbility(this));

        // The spell targets but does not do anything
        this.getSpellAbility().addTarget(new TargetPermanent());
    }

    private Glamerdye(final Glamerdye card) {
        super(card);
    }

    @Override
    public Glamerdye copy() {
        return new Glamerdye(this);
    }
}
