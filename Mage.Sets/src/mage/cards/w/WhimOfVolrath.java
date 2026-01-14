package mage.cards.w;

import mage.abilities.keyword.BuybackAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.target.TargetPermanent;

import java.util.UUID;

public final class WhimOfVolrath extends CardImpl {
    public WhimOfVolrath(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.INSTANT}, "{U}");

        // Buyback {2}
        this.addAbility(new BuybackAbility("{2}"));

        // The spell targets but does not do anything
        this.getSpellAbility().addTarget(new TargetPermanent());
    }

    private WhimOfVolrath(final WhimOfVolrath card) {
        super(card);
    }

    @Override
    public WhimOfVolrath copy() {
        return new WhimOfVolrath(this);
    }
}
