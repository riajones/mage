package org.mage.card.arcane;

import mage.ObjectColor;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.common.InfoEffect;
import mage.cards.FrameStyle;
import mage.cards.repository.CardInfo;
import mage.client.util.CardRenderMode;
import mage.constants.Zone;
import mage.view.AbilityView;
import mage.view.CardView;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;

public class CardRendererFactoryTest {

    @Test
    public void testCardViewDefaults() {
        CardView cardView = new CardView(true);
        Assert.assertNotNull(cardView.getFrameStyle());
        Assert.assertNotNull(cardView.getFrameColor());
        Assert.assertEquals(FrameStyle.M15_NORMAL, cardView.getFrameStyle());
    }

    @Test
    public void testAbilityViewNonNullFrameStyle() {
        AbilityView abilityView = new AbilityView(new SimpleStaticAbility(Zone.ALL, new InfoEffect("test")), "test", null);
        Assert.assertNotNull(abilityView.getFrameStyle());
        Assert.assertNotNull(abilityView.getFrameColor());
        Assert.assertEquals(FrameStyle.M15_NORMAL, abilityView.getFrameStyle());
    }

    @Test
    public void testCreateRendererWithAbilityView() {
        AbilityView abilityView = new AbilityView(new SimpleStaticAbility(Zone.ALL, new InfoEffect("test")), "test", null);
        CardRendererFactory factory = new CardRendererFactory();
        CardRenderer renderer = factory.create(abilityView);
        Assert.assertNotNull(renderer);
    }

    @Test
    public void testCreateRendererWithNullFrameStyleCardViewDoesNotThrow() throws Exception {
        CardView cardView = new CardView(true);
        // Force frameStyle to null via reflection to simulate corrupted/uninitialized serialized card view
        Field frameStyleField = CardView.class.getDeclaredField("frameStyle");
        frameStyleField.setAccessible(true);
        frameStyleField.set(cardView, null);

        CardRendererFactory factory = new CardRendererFactory();
        CardRenderer renderer = factory.create(cardView, CardRenderMode.MTGO.ordinal());
        Assert.assertNotNull(renderer);
    }

    @Test
    public void testObjectColorUnionNullSafe() {
        ObjectColor color = new ObjectColor("W");
        ObjectColor union = color.union(null);
        Assert.assertNotNull(union);
        Assert.assertTrue(union.isWhite());
        Assert.assertFalse(union.isBlue());
    }

    @Test
    public void testCardInfoNullSafe() throws Exception {
        CardInfo cardInfo = new CardInfo();
        Assert.assertNotNull(cardInfo.getFrameStyle());
        Assert.assertEquals(FrameStyle.M15_NORMAL, cardInfo.getFrameStyle());
        Assert.assertNotNull(cardInfo.getFrameColor());
    }
}
