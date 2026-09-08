package com.example.cleanrecovery.ui.browser;

import android.content.Context;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.tables.TableAwareMovementMethod;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tables.TableTheme;
import io.noties.markwon.movement.MovementMethodPlugin;

/** Native Markdown configuration corresponding to Via's ra.c renderer. */
public final class BrowserAiMarkdown {
    private BrowserAiMarkdown() { }

    public static Markwon create(Context context, java.util.function.Consumer<String> openLink) {
        float density = context.getResources().getDisplayMetrics().density;
        int pixel = Math.max(1, Math.round(density));
        return Markwon.builder(context)
                .usePlugin(TablePlugin.create(TableTheme.emptyBuilder()
                        .tableBorderColor(0x40808080).tableBorderWidth(pixel)
                        .tableCellPadding(Math.round(4 * density))
                        .tableHeaderRowBackgroundColor(0).tableEvenRowBackgroundColor(0)
                        .tableOddRowBackgroundColor(0).build()))
                .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override public void configureTheme(MarkwonTheme.Builder builder) {
                        builder.blockQuoteWidth(pixel).thematicBreakHeight(pixel)
                                .blockQuoteColor(0x20808080).thematicBreakColor(0x20808080);
                    }
                    @Override public void configureConfiguration(MarkwonConfiguration.Builder builder) {
                        builder.linkResolver((view, link) -> openLink.accept(link));
                    }
                }).build();
    }
}
