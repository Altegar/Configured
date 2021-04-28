package com.mrcrayfish.configured.client.screen;

import com.electronwill.nightconfig.core.AbstractConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mrcrayfish.configured.Configured;
import com.mrcrayfish.configured.client.screen.widget.IconButton;
import com.mrcrayfish.configured.client.util.ScreenUtil;
import joptsimple.internal.Strings;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.list.AbstractOptionList;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Author: MrCrayfish
 */
@OnlyIn(Dist.CLIENT)
public class ConfigScreen extends Screen
{
    public static final ResourceLocation LOGO_TEXTURE = new ResourceLocation("configured", "textures/gui/logo.png");

    public static final Comparator<Entry> COMPARATOR = (o1, o2) -> {
        if(o1 instanceof SubMenu && o2 instanceof SubMenu)
        {
            return o1.getLabel().compareTo(o2.getLabel());
        }
        if(!(o1 instanceof SubMenu) && o2 instanceof SubMenu)
        {
            return 1;
        }
        if(o1 instanceof SubMenu)
        {
            return -1;
        }
        return o1.getLabel().compareTo(o2.getLabel());
    };

    private final Screen parent;
    private final String displayName;
    private final ForgeConfigSpec clientSpec;
    private final UnmodifiableConfig clientValues;
    private final ForgeConfigSpec commonSpec;
    private final UnmodifiableConfig commonValues;
    private final ResourceLocation background;
    private ConfigList list;
    private List<Entry> entries;
    private ConfigTextFieldWidget activeTextField;
    private ConfigTextFieldWidget searchTextField;
    private Button restoreDefaultsButton;
    private boolean subMenu = false;
    private List<String> activeTooltip;
    private final List<Pair<ForgeConfigSpec.ConfigValue<?>, ForgeConfigSpec.ValueSpec>> allConfigValues;

    public ConfigScreen(Screen parent, String displayName, ForgeConfigSpec spec, UnmodifiableConfig values, ResourceLocation background)
    {
        super(new StringTextComponent(displayName));
        this.parent = parent;
        this.displayName = displayName;
        this.clientSpec = spec;
        this.clientValues = values;
        this.commonSpec = null;
        this.commonValues = null;
        this.subMenu = true;
        this.allConfigValues = null;
        this.background = background;
    }

    public ConfigScreen(Screen parent, String displayName, @Nullable ForgeConfigSpec clientSpec, @Nullable ForgeConfigSpec commonSpec, ResourceLocation background)
    {
        super(new StringTextComponent(displayName));
        this.parent = parent;
        this.displayName = displayName;
        this.clientSpec = clientSpec;
        this.clientValues = clientSpec != null ? clientSpec.getValues() : null;
        this.commonSpec = commonSpec;
        this.commonValues = commonSpec != null ? commonSpec.getValues() : null;
        this.allConfigValues = this.gatherAllConfigValues();
        this.background = background;
    }

    /**
     * Gathers all the config values with a deep search. Used for resetting defaults
     */
    private List<Pair<ForgeConfigSpec.ConfigValue<?>, ForgeConfigSpec.ValueSpec>> gatherAllConfigValues()
    {
        List<Pair<ForgeConfigSpec.ConfigValue<?>, ForgeConfigSpec.ValueSpec>> values = new ArrayList<>();
        if(this.clientValues != null) this.gatherValuesFromConfig(this.clientValues, this.clientSpec, values);
        if(this.commonValues != null) this.gatherValuesFromConfig(this.commonValues, this.commonSpec, values);
        return ImmutableList.copyOf(values);
    }

    /**
     * Gathers all the config values from the given config and adds it's to the provided list. This
     * will search deeper if it finds another config and recursively call itself.
     */
    private void gatherValuesFromConfig(UnmodifiableConfig config, ForgeConfigSpec spec, List<Pair<ForgeConfigSpec.ConfigValue<?>, ForgeConfigSpec.ValueSpec>> values)
    {
        config.valueMap().forEach((s, o) ->
        {
            if(o instanceof AbstractConfig)
            {
                this.gatherValuesFromConfig((UnmodifiableConfig) o, spec, values);
            }
            else if(o instanceof ForgeConfigSpec.ConfigValue<?>)
            {
                ForgeConfigSpec.ConfigValue<?> configValue = (ForgeConfigSpec.ConfigValue<?>) o;
                ForgeConfigSpec.ValueSpec valueSpec = spec.getRaw(configValue.getPath());
                values.add(Pair.of(configValue, valueSpec));
            }
        });
    }

    /**
     * Gathers the entries for each config spec to be later added to the option list
     */
    private void constructEntries()
    {
        List<Entry> entries = new ArrayList<>();
        if(this.clientValues != null && this.clientSpec != null)
        {
            if(!this.subMenu) entries.add(new TitleEntry("Client Configuration"));
            this.createEntriesFromConfig(this.clientValues, this.clientSpec, entries);
        }
        if(this.commonValues != null && this.commonSpec != null)
        {
            entries.add(new TitleEntry("Common Configuration"));
            this.createEntriesFromConfig(this.commonValues, this.commonSpec, entries);
        }
        this.entries = ImmutableList.copyOf(entries);
    }

    /**
     * Scans the given unmodifiable config and creates an entry for each scanned
     * config value based on it's type.
     *
     * @param values  the values to scan
     * @param spec    the spec of config
     * @param entries the list to add the entries to
     */
    private void createEntriesFromConfig(UnmodifiableConfig values, ForgeConfigSpec spec, List<Entry> entries)
    {
        List<Entry> subEntries = new ArrayList<>();
        values.valueMap().forEach((s, o) ->
        {
            if(o instanceof AbstractConfig)
            {
                subEntries.add(new SubMenu(s, spec, (AbstractConfig) o));
            }
            else if(o instanceof ForgeConfigSpec.ConfigValue<?>)
            {
                ForgeConfigSpec.ConfigValue<?> configValue = (ForgeConfigSpec.ConfigValue<?>) o;
                ForgeConfigSpec.ValueSpec valueSpec = spec.getRaw(configValue.getPath());
                Object value = configValue.get();
                if(value instanceof Boolean)
                {
                    subEntries.add(new BooleanEntry((ForgeConfigSpec.ConfigValue<Boolean>) configValue, valueSpec));
                }
                else if(value instanceof Integer)
                {
                    subEntries.add(new IntegerEntry((ForgeConfigSpec.ConfigValue<Integer>) configValue, valueSpec));
                }
                else if(value instanceof Double)
                {
                    subEntries.add(new DoubleEntry((ForgeConfigSpec.ConfigValue<Double>) configValue, valueSpec));
                }
                else if(value instanceof Long)
                {
                    subEntries.add(new LongEntry((ForgeConfigSpec.ConfigValue<Long>) configValue, valueSpec));
                }
                else if(value instanceof Enum)
                {
                    subEntries.add(new EnumEntry((ForgeConfigSpec.ConfigValue<Enum>) configValue, valueSpec));
                }
                else if(value instanceof String)
                {
                    subEntries.add(new StringEntry((ForgeConfigSpec.ConfigValue<String>) configValue, valueSpec));
                }
                else if(value instanceof List<?>)
                {
                    subEntries.add(new ListStringEntry((ForgeConfigSpec.ConfigValue<List<?>>) configValue, valueSpec));
                }
                else
                {
                    Configured.LOGGER.info("Unsupported config value: " + configValue.getPath());
                }
            }
        });
        subEntries.sort(COMPARATOR);
        entries.addAll(subEntries);
    }

    @Override
    protected void init()
    {
        this.constructEntries();

        this.list = new ConfigList(this.entries);
        this.children.add(this.list);

        this.searchTextField = new ConfigTextFieldWidget(this.font, this.width / 2 - 110, 22, 220, 20, new StringTextComponent("Search"));
        this.searchTextField.setResponder(s -> {
            if(!s.isEmpty())
            {
                this.list.replaceEntries(this.entries.stream().filter(entry -> (entry instanceof SubMenu || entry instanceof ConfigEntry<?>) && entry.getLabel().toLowerCase(Locale.ENGLISH).contains(s.toLowerCase(Locale.ENGLISH))).collect(Collectors.toList()));
            }
            else
            {
                this.list.replaceEntries(this.entries);
            }
        });
        this.children.add(this.searchTextField);

        if(this.subMenu)
        {
            this.addButton(new Button(this.width / 2 - 75, this.height - 29, 150, 20, I18n.format("gui.back"), (button) -> {
                this.minecraft.displayGuiScreen(this.parent);
            }));
        }
        else
        {
            this.addButton(new Button(this.width / 2 - 155 + 160, this.height - 29, 150, 20, I18n.format("gui.done"), (button) -> {
                if(this.clientSpec != null) this.clientSpec.save();
                if(this.commonSpec != null) this.commonSpec.save();
                this.minecraft.displayGuiScreen(this.parent);
            }));
            this.restoreDefaultsButton = this.addButton(new Button(this.width / 2 - 155, this.height - 29, 150, 20, I18n.format("configured.gui.restore_defaults"), (button) -> {
                if(this.allConfigValues == null)
                    return;
                // Resets all config values
                this.allConfigValues.forEach(pair -> {
                    ForgeConfigSpec.ConfigValue configValue = pair.getLeft();
                    ForgeConfigSpec.ValueSpec valueSpec = pair.getRight();
                    configValue.set(valueSpec.getDefault());
                });
                // Updates the current entries to process UI changes
                this.entries.stream().filter(entry -> entry instanceof ConfigEntry).forEach(entry -> {
                    ((ConfigEntry) entry).onResetValue();
                });
            }));
            // Call during init to avoid the button flashing active
            this.updateRestoreDefaultButton();
        }
    }

    @Override
    public void tick()
    {
        this.updateRestoreDefaultButton();
    }

    /**
     * Updates the active state of the restore default button. It will only be active if values are
     * different from their default.
     */
    private void updateRestoreDefaultButton()
    {
        if(this.allConfigValues != null && this.restoreDefaultsButton != null)
        {
            this.restoreDefaultsButton.active = this.allConfigValues.stream().anyMatch(pair -> !pair.getLeft().get().equals(pair.getRight().getDefault()));
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks)
    {
        this.activeTooltip = null;
        this.renderBackground();
        this.list.render(mouseX, mouseY, partialTicks);
        this.searchTextField.render(mouseX, mouseY, partialTicks);
        drawCenteredString(this.font, this.title.getFormattedText(), this.width / 2, 7, 0xFFFFFF);
        super.render(mouseX, mouseY, partialTicks);
        this.minecraft.getTextureManager().bindTexture(LOGO_TEXTURE);
        blit(10, 13, this.getBlitOffset(), 0, 0, 23, 23, 32, 32);
        if(ScreenUtil.isMouseWithin(10, 13, 23, 23, mouseX, mouseY))
        {
            this.setActiveTooltip(this.minecraft.fontRenderer.listFormattedStringToWidth(I18n.format("configured.gui.info"), 200));
        }
        if(this.activeTooltip != null)
        {
            this.renderTooltip(this.activeTooltip, mouseX, mouseY);
        }
        this.children().forEach(o ->
        {
            if(o instanceof IconButton.Tooltip)
            {
                ((IconButton.Tooltip) o).onTooltip((Button) o, mouseX, mouseY);
            }
        });
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        if(ScreenUtil.isMouseWithin(10, 13, 23, 23, (int) mouseX, (int) mouseY))
        {
            Style style = new Style().setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://www.curseforge.com/minecraft/mc-mods/configured"));
            ITextComponent placeholder = new StringTextComponent("").setStyle(style);
            this.handleComponentClicked(placeholder);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Sets the tool tip to render. Must be actively called in the render method as
     * the tooltip is reset every draw call.
     *
     * @param activeTooltip a tooltip list to show
     */
    public void setActiveTooltip(List<String> activeTooltip)
    {
        this.activeTooltip = activeTooltip;
    }

    abstract class Entry extends AbstractOptionList.Entry<Entry>
    {
        protected String label;
        protected List<String> tooltip;

        public Entry(String label)
        {
            this.label = label;
        }

        public String getLabel()
        {
            return this.label;
        }
    }

    public class TitleEntry extends Entry
    {
        public TitleEntry(String title)
        {
            super(title);
        }

        @Override
        public List<? extends IGuiEventListener> children()
        {
            return Collections.emptyList();
        }

        @Override
        public void render(int x, int top, int left, int width, int p_230432_6_, int p_230432_7_, int p_230432_8_, boolean p_230432_9_, float p_230432_10_)
        {
            ITextComponent title = new StringTextComponent(this.label).applyTextStyle(TextFormatting.BOLD).applyTextStyle(TextFormatting.YELLOW);
            ConfigScreen.this.drawCenteredString(ConfigScreen.this.minecraft.fontRenderer, title.getFormattedText(), left + width / 2, top + 5, 16777215);
        }
    }

    public class SubMenu extends Entry
    {
        private final Button button;

        public SubMenu(String label, ForgeConfigSpec spec, AbstractConfig values)
        {
            super(createLabel(label));
            this.button = new Button(10, 5, 44, 20, new StringTextComponent(this.getLabel()).applyTextStyle(TextFormatting.BOLD).applyTextStyle(TextFormatting.WHITE).getFormattedText(), onPress -> {
                String newTitle = ConfigScreen.this.displayName + " > " + this.getLabel();
                ConfigScreen.this.minecraft.displayGuiScreen(new ConfigScreen(ConfigScreen.this, newTitle, spec, values, background));
            });
        }

        @Override
        public List<? extends IGuiEventListener> children()
        {
            return ImmutableList.of(this.button);
        }

        @Override
        public void render(int x, int top, int left, int width, int height, int mouseX, int mouseY, boolean selected, float partialTicks)
        {
            this.button.x = left - 1;
            this.button.y = top;
            this.button.setWidth(width);
            this.button.render(mouseX, mouseY, partialTicks);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public abstract class ConfigEntry<T extends ForgeConfigSpec.ConfigValue> extends Entry
    {
        protected T configValue;
        protected ForgeConfigSpec.ValueSpec valueSpec;
        protected final List<IGuiEventListener> eventListeners = Lists.newArrayList();
        protected Button resetButton;

        public ConfigEntry(T configValue, ForgeConfigSpec.ValueSpec valueSpec)
        {
            super(createLabelFromConfig(configValue, valueSpec));
            this.configValue = configValue;
            this.valueSpec = valueSpec;
            if(valueSpec.getComment() != null)
            {
                this.tooltip = this.createToolTip(configValue, valueSpec);
            }
            IconButton.Tooltip tooltip = (button, mouseX, mouseY) ->
            {
                if(button.active && button.isHovered())
                {
                    ConfigScreen.this.renderTooltip(ConfigScreen.this.minecraft.fontRenderer.listFormattedStringToWidth(new TranslationTextComponent("configured.gui.reset").getFormattedText(), Math.max(ConfigScreen.this.width / 2 - 43, 170)), mouseX, mouseY);
                }
            };
            this.resetButton = new IconButton(0, 0, 20, 20, 0, 0, tooltip, onPress -> {
                configValue.set(valueSpec.getDefault());
                this.onResetValue();
            });
            this.eventListeners.add(this.resetButton);
        }

        public void onResetValue() {}

        @Override
        public List<? extends IGuiEventListener> children()
        {
            return this.eventListeners;
        }

        @Override
        public void render(int x, int top, int left, int width, int p_230432_6_, int mouseX, int mouseY, boolean hovered, float partialTicks)
        {
            this.resetButton.active = !this.configValue.get().equals(this.valueSpec.getDefault());

            ITextComponent title = new StringTextComponent(this.label);
            if(ConfigScreen.this.minecraft.fontRenderer.getStringWidth(title.getUnformattedComponentText()) > width - 50)
            {
                String trimmed = ConfigScreen.this.minecraft.fontRenderer.trimStringToWidth(title.getFormattedText(), width - 50) + "...";
                ConfigScreen.this.minecraft.fontRenderer.drawStringWithShadow(new StringTextComponent(trimmed).getText(), left, top + 6, 0xFFFFFF);
            }
            else
            {
                ConfigScreen.this.minecraft.fontRenderer.drawStringWithShadow(title.getFormattedText(), left, top + 6, 0xFFFFFF);
            }

            if(this.isMouseOver(mouseX, mouseY) && mouseX < ConfigScreen.this.list.getRowLeft() + ConfigScreen.this.list.getRowWidth() - 67)
            {
                ConfigScreen.this.setActiveTooltip(this.tooltip);
            }

            this.resetButton.x = left + width - 21;
            this.resetButton.y = top;
            this.resetButton.render(mouseX, mouseY, partialTicks);
        }

        private List<String> createToolTip(ForgeConfigSpec.ConfigValue<?> value, ForgeConfigSpec.ValueSpec spec)
        {
            FontRenderer font = ConfigScreen.this.minecraft.fontRenderer;
            List<String> lines = new ArrayList<>(font.listFormattedStringToWidth(new StringTextComponent(spec.getComment()).getText(), 200));
            String name = lastValue(value.getPath(), "");
            lines.add(0, new StringTextComponent(name).applyTextStyle(TextFormatting.YELLOW).getFormattedText());
            int rangeIndex = -1;
            for(int i = 0; i < lines.size(); i++)
            {
                String text = lines.get(i);
                if(text.startsWith("Range: ") || text.startsWith("Allowed Values: "))
                {
                    rangeIndex = i;
                    break;
                }
            }
            if(rangeIndex != -1)
            {
                for(int i = rangeIndex; i < lines.size(); i++)
                {
                    lines.set(i, new StringTextComponent(lines.get(i)).applyTextStyle(TextFormatting.GRAY).getFormattedText());
                }
            }
            return lines;
        }
    }

    @OnlyIn(Dist.CLIENT)
    public class ConfigList extends AbstractOptionList<ConfigScreen.Entry>
    {
        public ConfigList(List<ConfigScreen.Entry> entries)
        {
            super(ConfigScreen.this.minecraft, ConfigScreen.this.width, ConfigScreen.this.height, 50, ConfigScreen.this.height - 36, 24);
            entries.forEach(this::addEntry);
        }

        @Override
        public int getRowLeft()
        {
            return super.getRowLeft();
        }

        @Override
        protected int getScrollbarPosition()
        {
            return this.width / 2 + 144;
        }

        @Override
        public int getRowWidth()
        {
            return 260;
        }

        @Override
        public void replaceEntries(Collection<ConfigScreen.Entry> entries)
        {
            super.replaceEntries(entries);
        }

        private void renderToolTips(int mouseX, int mouseY)
        {
            if(this.isMouseOver(mouseX, mouseY) && mouseX < ConfigScreen.this.list.getRowLeft() + ConfigScreen.this.list.getRowWidth() - 67)
            {
                ConfigScreen.Entry entry = this.getEntryAtPosition(mouseX, mouseY);
                if(entry != null)
                {
                    ConfigScreen.this.setActiveTooltip(entry.tooltip);
                }
            }
            this.children().forEach(entry ->
            {
                entry.children().forEach(o ->
                {
                    if(o instanceof Button)
                    {
                        ((Button) o).renderToolTip(mouseX, mouseY);
                    }
                });
            });
        }

        /**
         * Literally just a copy of the original since the background can't be changed
         *
         * @param mouseX       the current mouse x position
         * @param mouseY       the current mouse y position
         * @param partialTicks the partial ticks
         */
        @Override
        public void render(int mouseX, int mouseY, float partialTicks)
        {
            this.renderBackground();
            int scrollBarStart = this.getScrollbarPosition();
            int scrollBarEnd = scrollBarStart + 6;
            this.minecraft.getTextureManager().bindTexture(background);

            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();

            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            buffer.pos(this.x0, this.y1, 0.0D).tex(this.x0 / 32.0F, (this.y1 + (int) this.getScrollAmount()) / 32.0F).color(32, 32, 32, 255).endVertex();
            buffer.pos(this.x1, this.y1, 0.0D).tex(this.x1 / 32.0F, (this.y1 + (int) this.getScrollAmount()) / 32.0F).color(32, 32, 32, 255).endVertex();
            buffer.pos(this.x1, this.y0, 0.0D).tex(this.x1 / 32.0F, (this.y0 + (int) this.getScrollAmount()) / 32.0F).color(32, 32, 32, 255).endVertex();
            buffer.pos(this.x0, this.y0, 0.0D).tex(this.x0 / 32.0F, (this.y0 + (int) this.getScrollAmount()) / 32.0F).color(32, 32, 32, 255).endVertex();
            tessellator.draw();

            int rowLeft = this.getRowLeft();
            int scrollOffset = this.y0 + 4 - (int) this.getScrollAmount();
            this.renderList(rowLeft, scrollOffset, mouseX, mouseY, partialTicks);
            this.minecraft.getTextureManager().bindTexture(background);

            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(519);

            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            buffer.pos(this.x0, this.y0, -100.0D).tex(0.0F, this.y0 / 32.0F).color(64, 64, 64, 255).endVertex();
            buffer.pos((this.x0 + this.width), this.y0, -100.0D).tex(this.width / 32.0F, this.y0 / 32.0F).color(64, 64, 64, 255).endVertex();
            buffer.pos((this.x0 + this.width), 0.0D, -100.0D).tex(this.width / 32.0F, 0.0F).color(64, 64, 64, 255).endVertex();
            buffer.pos(this.x0, 0.0D, -100.0D).tex(0.0F, 0.0F).color(64, 64, 64, 255).endVertex();
            buffer.pos(this.x0, this.height, -100.0D).tex(0.0F, this.height / 32.0F).color(64, 64, 64, 255).endVertex();
            buffer.pos((this.x0 + this.width), this.height, -100.0D).tex(this.width / 32.0F, this.height / 32.0F).color(64, 64, 64, 255).endVertex();
            buffer.pos((this.x0 + this.width), this.y1, -100.0D).tex(this.width / 32.0F, this.y1 / 32.0F).color(64, 64, 64, 255).endVertex();
            buffer.pos(this.x0, this.y1, -100.0D).tex(0.0F, this.y1 / 32.0F).color(64, 64, 64, 255).endVertex();
            tessellator.draw();

            RenderSystem.depthFunc(515);
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            RenderSystem.disableAlphaTest();
            RenderSystem.shadeModel(7425);
            RenderSystem.disableTexture();

            buffer.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);
            buffer.pos((double) this.x0, (double) (this.y0 + 4), 0.0D).tex(0.0F, 1.0F).color(0, 0, 0, 0).endVertex();
            buffer.pos((double) this.x1, (double) (this.y0 + 4), 0.0D).tex(1.0F, 1.0F).color(0, 0, 0, 0).endVertex();
            buffer.pos((double) this.x1, (double) this.y0, 0.0D).tex(1.0F, 0.0F).color(0, 0, 0, 255).endVertex();
            buffer.pos((double) this.x0, (double) this.y0, 0.0D).tex(0.0F, 0.0F).color(0, 0, 0, 255).endVertex();
            buffer.pos((double) this.x0, (double) this.y1, 0.0D).tex(0.0F, 1.0F).color(0, 0, 0, 255).endVertex();
            buffer.pos((double) this.x1, (double) this.y1, 0.0D).tex(1.0F, 1.0F).color(0, 0, 0, 255).endVertex();
            buffer.pos((double) this.x1, (double) (this.y1 - 4), 0.0D).tex(1.0F, 0.0F).color(0, 0, 0, 0).endVertex();
            buffer.pos((double) this.x0, (double) (this.y1 - 4), 0.0D).tex(0.0F, 0.0F).color(0, 0, 0, 0).endVertex();
            tessellator.draw();

            int maxScroll = Math.max(0, this.getMaxPosition() - (this.y1 - this.y0 - 4));
            if(maxScroll > 0)
            {
                int scrollBarStartY = (int) ((float) ((this.y1 - this.y0) * (this.y1 - this.y0)) / (float) this.getMaxPosition());
                scrollBarStartY = MathHelper.clamp(scrollBarStartY, 32, this.y1 - this.y0 - 8);
                int scrollBarEndY = (int) this.getScrollAmount() * (this.y1 - this.y0 - scrollBarStartY) / maxScroll + this.y0;
                if(scrollBarEndY < this.y0)
                {
                    scrollBarEndY = this.y0;
                }

                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                buffer.pos((double) scrollBarStart, (double) this.y1, 0.0D).tex(0.0F, 1.0F).color(0, 0, 0, 255).endVertex();
                buffer.pos((double) scrollBarEnd, (double) this.y1, 0.0D).tex(1.0F, 1.0F).color(0, 0, 0, 255).endVertex();
                buffer.pos((double) scrollBarEnd, (double) this.y0, 0.0D).tex(1.0F, 0.0F).color(0, 0, 0, 255).endVertex();
                buffer.pos((double) scrollBarStart, (double) this.y0, 0.0D).tex(0.0F, 0.0F).color(0, 0, 0, 255).endVertex();
                buffer.pos((double) scrollBarStart, (double) (scrollBarEndY + scrollBarStartY), 0.0D).tex(0.0F, 1.0F).color(128, 128, 128, 255).endVertex();
                buffer.pos((double) scrollBarEnd, (double) (scrollBarEndY + scrollBarStartY), 0.0D).tex(1.0F, 1.0F).color(128, 128, 128, 255).endVertex();
                buffer.pos((double) scrollBarEnd, (double) scrollBarEndY, 0.0D).tex(1.0F, 0.0F).color(128, 128, 128, 255).endVertex();
                buffer.pos((double) scrollBarStart, (double) scrollBarEndY, 0.0D).tex(0.0F, 0.0F).color(128, 128, 128, 255).endVertex();
                buffer.pos((double) scrollBarStart, (double) (scrollBarEndY + scrollBarStartY - 1), 0.0D).tex(0.0F, 1.0F).color(192, 192, 192, 255).endVertex();
                buffer.pos((double) (scrollBarEnd - 1), (double) (scrollBarEndY + scrollBarStartY - 1), 0.0D).tex(1.0F, 1.0F).color(192, 192, 192, 255).endVertex();
                buffer.pos((double) (scrollBarEnd - 1), (double) scrollBarEndY, 0.0D).tex(1.0F, 0.0F).color(192, 192, 192, 255).endVertex();
                buffer.pos((double) scrollBarStart, (double) scrollBarEndY, 0.0D).tex(0.0F, 0.0F).color(192, 192, 192, 255).endVertex();
                tessellator.draw();
            }

            this.renderDecorations(mouseX, mouseY);

            RenderSystem.enableTexture();
            RenderSystem.shadeModel(7424);
            RenderSystem.enableAlphaTest();
            RenderSystem.disableBlend();

            this.renderToolTips(mouseX, mouseY);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public abstract class NumberEntry<T extends ForgeConfigSpec.ConfigValue> extends ConfigEntry<T>
    {
        private ConfigTextFieldWidget textField;

        public NumberEntry(T configValue, ForgeConfigSpec.ValueSpec valueSpec, Function<String, Number> parser)
        {
            super(configValue, valueSpec);
            this.textField = new ConfigTextFieldWidget(ConfigScreen.this.font, 0, 0, 42, 18, new StringTextComponent("YEP"));
            this.textField.setText(configValue.get().toString());
            this.textField.setResponder((s) -> {
                try
                {
                    Number n = parser.apply(s);
                    if(valueSpec.test(n))
                    {
                        this.textField.setTextColor(14737632);
                        //noinspection unchecked
                        configValue.set(n);
                    }
                    else
                    {
                        this.textField.setTextColor(16711680);
                    }
                }
                catch(Exception ignored)
                {
                    this.textField.setTextColor(16711680);
                }
            });
            this.eventListeners.add(this.textField);
        }

        @Override
        public void render(int index, int top, int left, int width, int p_230432_6_, int mouseX, int mouseY, boolean hovered, float partialTicks)
        {
            super.render(index, top, left, width, p_230432_6_, mouseX, mouseY, hovered, partialTicks);
            this.textField.x = left + width - 66;
            this.textField.y = top + 1;
            this.textField.render(mouseX, mouseY, partialTicks);
        }

        @Override
        public void onResetValue()
        {
            this.textField.setText(this.configValue.get().toString());
        }
    }

    @OnlyIn(Dist.CLIENT)
    public class IntegerEntry extends NumberEntry<ForgeConfigSpec.ConfigValue<Integer>>
    {
        public IntegerEntry(ForgeConfigSpec.ConfigValue<Integer> configValue, ForgeConfigSpec.ValueSpec valueSpec)
        {
            super(configValue, valueSpec, Integer::parseInt);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public class DoubleEntry extends NumberEntry<ForgeConfigSpec.ConfigValue<Double>>
    {
        public DoubleEntry(ForgeConfigSpec.ConfigValue<Double> configValue, ForgeConfigSpec.ValueSpec valueSpec)
        {
            super(configValue, valueSpec, Double::parseDouble);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public class LongEntry extends NumberEntry<ForgeConfigSpec.ConfigValue<Long>>
    {
        public LongEntry(ForgeConfigSpec.ConfigValue<Long> configValue, ForgeConfigSpec.ValueSpec valueSpec)
        {
            super(configValue, valueSpec, Long::parseLong);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public class BooleanEntry extends ConfigEntry<ForgeConfigSpec.ConfigValue<Boolean>>
    {
        private final Button button;

        public BooleanEntry(ForgeConfigSpec.ConfigValue<Boolean> configValue, ForgeConfigSpec.ValueSpec valueSpec)
        {
            super(configValue, valueSpec);
            this.button = new Button(10, 5, 44, 20, this.getLabel(), (button) -> {
                boolean flag = !configValue.get();
                configValue.set(flag);
                button.setMessage(this.getLabel());
            });
            this.eventListeners.add(this.button);
        }

        @Override
        public void render(int index, int top, int left, int width, int p_230432_6_, int mouseX, int mouseY, boolean hovered, float partialTicks)
        {
            super.render(index, top, left, width, p_230432_6_, mouseX, mouseY, hovered, partialTicks);
            this.button.x = left + width - 67;
            this.button.y = top;
            this.button.render(mouseX, mouseY, partialTicks);
        }

        @Override
        public void onResetValue()
        {
            this.button.setMessage(this.getLabel());
        }

        public String getLabel()
        {
            return I18n.format(this.configValue.get() ? "options.on" : "options.off");
        }
    }

    @OnlyIn(Dist.CLIENT)
    public class StringEntry extends ConfigEntry<ForgeConfigSpec.ConfigValue<String>>
    {
        private final Button button;

        public StringEntry(ForgeConfigSpec.ConfigValue<String> configValue, ForgeConfigSpec.ValueSpec valueSpec)
        {
            super(configValue, valueSpec);
            String title = createLabelFromConfig(configValue, valueSpec);
            this.button = new Button(10, 5, 44, 20, new TranslationTextComponent("configured.gui.edit").getFormattedText(), (button) -> {
                ConfigScreen.this.minecraft.displayGuiScreen(new EditStringScreen(ConfigScreen.this, new StringTextComponent(title), configValue.get(), valueSpec::test, configValue::set));
            });
            this.eventListeners.add(this.button);
        }

        @Override
        public void render(int index, int top, int left, int width, int p_230432_6_, int mouseX, int mouseY, boolean hovered, float partialTicks)
        {
            super.render(index, top, left, width, p_230432_6_, mouseX, mouseY, hovered, partialTicks);
            this.button.x = left + width - 67;
            this.button.y = top;
            this.button.render(mouseX, mouseY, partialTicks);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public class ListStringEntry extends ConfigEntry<ForgeConfigSpec.ConfigValue<List<?>>>
    {
        private final Button button;

        public ListStringEntry(ForgeConfigSpec.ConfigValue<List<?>> configValue, ForgeConfigSpec.ValueSpec valueSpec)
        {
            super(configValue, valueSpec);
            String title = createLabelFromConfig(configValue, valueSpec);
            this.button = new Button(10, 5, 44, 20, new TranslationTextComponent("configured.gui.edit").getFormattedText(), (button) -> {
                ConfigScreen.this.minecraft.displayGuiScreen(new EditStringListScreen(ConfigScreen.this, new StringTextComponent(title), configValue, valueSpec));
            });
            this.eventListeners.add(this.button);
        }

        @Override
        public void render(int index, int top, int left, int width, int p_230432_6_, int mouseX, int mouseY, boolean hovered, float partialTicks)
        {
            super.render(index, top, left, width, p_230432_6_, mouseX, mouseY, hovered, partialTicks);
            this.button.x = left + width - 67;
            this.button.y = top;
            this.button.render(mouseX, mouseY, partialTicks);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public class EnumEntry extends ConfigEntry<ForgeConfigSpec.ConfigValue<Enum>>
    {
        private final Button button;

        public EnumEntry(ForgeConfigSpec.ConfigValue<Enum> configValue, ForgeConfigSpec.ValueSpec valueSpec)
        {
            super(configValue, valueSpec);
            this.button = new Button(10, 5, 44, 20, new StringTextComponent(configValue.get().name()).getText(), (button) -> {
                Object o = configValue.get();
                if(o instanceof Enum)
                {
                    Enum e = (Enum) o;
                    Object[] values = e.getDeclaringClass().getEnumConstants();
                    e = (Enum) values[(e.ordinal() + 1) % values.length];
                    //noinspection unchecked
                    configValue.set(e);
                    button.setMessage(new StringTextComponent(e.name()).getText());
                }
            });
            this.eventListeners.add(this.button);
        }

        @Override
        public void render(int index, int top, int left, int width, int p_230432_6_, int mouseX, int mouseY, boolean hovered, float partialTicks)
        {
            super.render(index, top, left, width, p_230432_6_, mouseX, mouseY, hovered, partialTicks);
            this.button.x = left + width - 67;
            this.button.y = top;
            this.button.render(mouseX, mouseY, partialTicks);
        }

        @Override
        public void onResetValue()
        {
            this.button.setMessage(new StringTextComponent(this.configValue.get().name()).getText());
        }
    }

    /**
     * A custom implementation of the text field widget to help reset the focus when it's used
     * in an option list. This class is specific to {@link ConfigScreen} and won't work anywhere
     * else.
     */
    @OnlyIn(Dist.CLIENT)
    public class ConfigTextFieldWidget extends TextFieldWidget
    {
        public ConfigTextFieldWidget(FontRenderer fontRenderer, int x, int y, int width, int height, ITextComponent label)
        {
            super(fontRenderer, x, y, width, height, label.getFormattedText());
        }

        @Override
        public void setFocused2(boolean focused)
        {
            super.setFocused2(focused);
            if(focused)
            {
                if(ConfigScreen.this.activeTextField != null && ConfigScreen.this.activeTextField != this)
                {
                    ConfigScreen.this.activeTextField.setFocused2(false);
                    ConfigScreen.this.activeTextField = this;
                }
                else
                {
                    ConfigScreen.this.activeTextField = this;
                }
            }
        }
    }

    /**
     * Gets the last element in a list
     *
     * @param list         the list of get the value from
     * @param defaultValue if the list is empty, return this value instead
     * @param <V>          the type of list
     * @return the last element
     */
    private static <V> V lastValue(List<V> list, V defaultValue)
    {
        if(list.size() > 0)
        {
            return list.get(list.size() - 1);
        }
        return defaultValue;
    }

    /**
     * Tries to create a readable label from the given config value and spec. This will
     * first attempt to create a label from the translation key in the spec, otherwise it
     * will create a readable label from the raw config value name.
     *
     * @param configValue the config value
     * @param valueSpec   the associated value spec
     * @return a readable label string
     */
    private static String createLabelFromConfig(ForgeConfigSpec.ConfigValue<?> configValue, ForgeConfigSpec.ValueSpec valueSpec)
    {
        if(valueSpec.getTranslationKey() != null)
        {
            return new TranslationTextComponent(valueSpec.getTranslationKey()).getString();
        }
        return createLabel(lastValue(configValue.getPath(), ""));
    }

    /**
     * Tries to create a readable label from the given input. This input should be
     * the raw config value name. For example "shouldShowParticles" will be converted
     * to "Should Show Particles".
     *
     * @param input the config value name
     * @return a readable label string
     */
    private static String createLabel(String input)
    {
        String valueName = input;
        // Try split by camel case
        String[] words = valueName.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");
        for(int i = 0; i < words.length; i++) words[i] = StringUtils.capitalize(words[i]);
        valueName = Strings.join(words, " ");
        // Try split by underscores
        words = valueName.split("_");
        for(int i = 0; i < words.length; i++) words[i] = StringUtils.capitalize(words[i]);
        // Finally join words. Some mods have inputs like "Foo_Bar" and this causes a double space.
        // To fix this any whitespace is replaced with a single space
        return Strings.join(words, " ").replaceAll("\\s++", " ");
    }

    @Override
    public void renderDirtBackground(int vOffset)
    {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder bufferbuilder = tessellator.getBuffer();
        this.minecraft.getTextureManager().bindTexture(this.background);
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        float size = 32.0F;
        bufferbuilder.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);
        bufferbuilder.pos(0.0D, this.height, 0.0D).tex(0.0F, this.height / size + vOffset).color(64, 64, 64, 255).endVertex();
        bufferbuilder.pos(this.width, this.height, 0.0D).tex(this.width / size, this.height / size + vOffset).color(64, 64, 64, 255).endVertex();
        bufferbuilder.pos(this.width, 0.0D, 0.0D).tex(this.width / size, vOffset).color(64, 64, 64, 255).endVertex();
        bufferbuilder.pos(0.0D, 0.0D, 0.0D).tex(0.0F, vOffset).color(64, 64, 64, 255).endVertex();
        tessellator.draw();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.client.event.GuiScreenEvent.BackgroundDrawnEvent(this));
    }
}
