package mekanism.common.integration.lookingat.jade;

import java.util.Optional;
import mekanism.api.SerializationConstants;
import mekanism.client.render.IFancyFontRenderer.TextAlignment;
import mekanism.common.integration.lookingat.ILookingAtElement;
import mekanism.common.integration.lookingat.LookingAtElement;
import mekanism.common.integration.lookingat.TextElement;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import org.jetbrains.annotations.Nullable;
import snownee.jade.api.Accessor;
import snownee.jade.api.IComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.Element;

public class JadeTooltipRenderer<ACCESSOR extends Accessor<?>> implements IComponentProvider<ACCESSOR> {

    static final JadeTooltipRenderer<?> INSTANCE = new JadeTooltipRenderer<>();

    @Override
    public Identifier getUid() {
        return JadeConstants.TOOLTIP_RENDERER;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, ACCESSOR accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        Optional<ListTag> optionalData = data.getList(SerializationConstants.MEK_DATA);
        if (optionalData.isPresent()) {
            Component lastText = null;
            RegistryOps<Tag> registryOps = accessor.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            //Copy the data we need and have from the server and pass it on to the tooltip rendering
            ListTag list = optionalData.get();
            for (int i = 0; i < list.size(); i++) {
                //TODO - 26.1: Make this non capturing if jade doesn't end up switching to value inputs/outputs and we have to stay with using compound tags
                Optional<ILookingAtElement> lookingAtElement = list.getCompound(i).flatMap(compound -> JadeElementCodecs.ELEMENT_CODEC.parse(registryOps, compound).result());
                if (lookingAtElement.isEmpty()) {
                    //Error deserializing, skip it
                    continue;
                }
                ILookingAtElement element = lookingAtElement.get();
                if (element instanceof TextElement(Component text)) {
                    if (lastText != null) {//Fallback to printing the last text
                        tooltip.add(lastText);
                    }
                    lastText = text;
                } else {
                    Identifier name = element.getID();
                    if (config.get(name)) {
                        tooltip.add(new MekElement(lastText, (LookingAtElement) element).tag(name));
                    }
                    lastText = null;
                }
            }
            if (lastText != null) {
                tooltip.add(lastText);
            }
        }
    }

    private static class MekElement extends Element {

        public Element create(@Nullable Component text, LookingAtElement element) {
            MekElement mekElement = new MekElement(text, element);
            int width = element.getWidth();
            int height = element.getHeight() + 2;
            if (text != null) {
                width = Math.max(width, 96);
                height += 14;
            }
            return mekElement.size(width, height);
        }

        @Nullable
        private final Component text;
        private final LookingAtElement element;

        private MekElement(@Nullable Component text, LookingAtElement element) {
            this.element = element;
            this.text = text;
        }

        @Override
        @Nullable
        public Component getNarration() {
            return text;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTicks) {
            int x = getX();
            int y = getY();
            if (text != null) {
                element.drawScrollingString(guiGraphics, text, x,y + 3, TextAlignment.LEFT, 0xFFFFFF, 4, false);
                y += 13;
            }
            element.render(guiGraphics, x, y + 1);
        }
    }
}
