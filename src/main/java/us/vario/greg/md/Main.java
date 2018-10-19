package us.vario.greg.md;

import lombok.Data;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.*;
import picocli.CommandLine;

import java.io.*;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

@CommandLine.Command(description = "Ansi colorized text rendering of Markdown files",
                     name = "md", mixinStandardHelpOptions = true, version = "0.1")
public class Main
        implements Callable<Void>
{

    @CommandLine.Parameters(index = "0", description = "The file to read.", paramLabel = "FILE")
    private File file;

    @CommandLine.Option(names = {"-H", "--HTML"}, description = "render as html")
    private boolean html;

    public static void main(String[] args) {
        CommandLine.call(new Main(), args);
    }

    @Override
    public Void call() throws Exception {

        Parser parser = Parser.builder().build();
        try (FileInputStream is = new FileInputStream(file)) {

            Node document = parser.parseReader(new InputStreamReader(is));
            if (html) {
                HtmlRenderer renderer = HtmlRenderer.builder().build();
                renderer.render(document, System.out);
                return null;
            }
            HtmlRenderer renderer = HtmlRenderer.builder().
                    nodeRendererFactory(new MyCoreNodeRendererFactory(DEFAULT_COLORS)).build();
            renderer.render(document, System.out);

        }
        return null;
    }

    static Map<String, String> DEFAULT_COLORS;

    static {
        HashMap<String, String> map = new HashMap<>();
        map.put("code", "green");
        map.put("emphasis", "blue");
        map.put("strong", "orange");
        map.put("header", "red");
        map.put("bullet", "white");
        map.put("href", "orange");
        map.put("linkTitle", "green");
        DEFAULT_COLORS = Collections.unmodifiableMap(map);
    }

    private class MyCoreNodeRendererFactory
            implements HtmlNodeRendererFactory
    {
        final Map<String, String> colors;

        public MyCoreNodeRendererFactory(final Map<String, String> colors) {
            this.colors = colors;
        }

        @Override
        public NodeRenderer create(final HtmlNodeRendererContext context) {
            return new MyCoreNodeRenderer(context, colors);
        }
    }

    private class MyCoreNodeRenderer
            extends CoreHtmlNodeRenderer
    {
        final Map<String, String> colors;

        public MyCoreNodeRenderer(final HtmlNodeRendererContext context, final Map<String, String> colors) {
            super(context);
            this.colors = colors;
        }

        ArrayDeque<Ctx> ctxtStack = new ArrayDeque<Ctx>();

        @Override
        public void visit(final OrderedList orderedList) {
            ctxtStack.push(new Ctx(orderedList, orderedList.getStartNumber()));
            renderListBlock(orderedList);
            ctxtStack.pop();
        }

        @Override
        public void visit(final BulletList bulletList) {
            ctxtStack.push(new Ctx(bulletList));
            renderListBlock(bulletList);
            ctxtStack.pop();
        }

        private void renderListBlock(ListBlock listBlock) {
            html().line();
            if (ctxtStack.size() > 0) {
                ctxtStack.peek().withType(OrderedList.class, (ctx, node) -> {
                    ctx.setPrefix(Ansi.colorize(ctx.nextListItemIndex() + ". ", colors.get("bullet")));
                });

                ctxtStack.peek().withType(BulletList.class, (ctx, node) -> {
                    ctx.setPrefix(Ansi.colorize(node.getBulletMarker() + " ", colors.get("bullet")));
                });
            }
            visitChildren(listBlock);

            html().text("\n");

            if (ctxtStack.size() > 0) {
                ctxtStack.peek().withType(OrderedList.class, (ctx, node) -> {
                    ctx.setPrefix(null);
                });

                ctxtStack.peek().withType(BulletList.class, (ctx, node) -> {
                    ctx.setPrefix(null);
                });
            }
        }

        @Override
        public void visit(final ListItem listItem) {
            if (ctxtStack.size() > 0) {
                if (ctxtStack.peek().getPrefix() != null) {
                    html().text(ctxtStack.peek().prefix);
                }
            }
            visitChildren(listItem);
            html().line();
        }

        private boolean isInTightList(Paragraph paragraph) {
            Node parent = paragraph.getParent();
            if (parent != null) {
                Node gramps = parent.getParent();
                if (gramps != null && gramps instanceof ListBlock) {
                    ListBlock list = (ListBlock) gramps;
                    return list.isTight();
                }
            }
            return false;
        }

        @Override
        public void visit(final Heading heading) {

            html().line();
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < heading.getLevel(); i++) {
                h.append("#");
            }
            html().text(Ansi.colorize(h.toString(), colors.get("header")));
            html().text(" ");
            visitChildren(heading);
            html().text("\n\n");
        }

        @Override
        public void visit(final Paragraph paragraph) {
            boolean inTightList = isInTightList(paragraph);
            if (!inTightList) {
                html().line();
            }
            visitChildren(paragraph);
            if (!inTightList) {
                html().text("\n\n");
            }
        }

        @Override
        public void visit(final Code code) {
            html().text(Ansi.beginColor(colors.get("code")));
            html().text("`");
            html().text(code.getLiteral());
            html().text("`");
            html().text(Ansi.reset);
        }

        @Override
        public void visit(final Emphasis emphasis) {
            beginDelimited(emphasis, colors.get("emphasis"));
            visitChildren(emphasis);
            endDelimited(emphasis);
        }


        private void endDelimited(final Delimited emphasis) {
            html().text(emphasis.getClosingDelimiter());
            html().text(Ansi.reset);
        }

        private void beginDelimited(final Delimited emphasis, final String color) {
            html().text(Ansi.beginColor(color));
            html().text(emphasis.getOpeningDelimiter());
        }

        @Override
        public void visit(final StrongEmphasis strongEmphasis) {
            beginDelimited(strongEmphasis, colors.get("strong"));
            visitChildren(strongEmphasis);
            endDelimited(strongEmphasis);
        }

        @Override
        public void visit(final Link link) {
            String url = context.encodeUrl(link.getDestination());

            if (link.getTitle() != null) {
                //    attrs.put("title", link.getTitle());
            }
//            html.tag("a", getAttrs(link, "a", attrs));
            html().text("[");
            visitChildren(link);
            html().text("](");
            html().text(Ansi.colorize(url, colors.get("href")));
            if (link.getTitle() != null) {
                html().text(Ansi.beginColor(colors.get("linkTitle")));
                html().raw(" \"");
                html().text(link.getTitle());
                html().raw("\"");
                html().raw(Ansi.reset);
            }
            html().text(")");
//            html.tag("/a");
        }

        private HtmlWriter html() {
            return context.getWriter();
        }

        @Data
        private class Ctx {
            final Node node;
            String prefix;

            public Ctx(final Node node) {
                this.node = node;
            }

            public Ctx(final Node node, final int olIndex) {
                this.node = node;
                this.olIndex = olIndex;
            }

            int olIndex = -1;

            public int nextListItemIndex() {
                if (olIndex <= 0) {
                    olIndex = 1;
                }
                return olIndex++;
            }

            public <T extends Node> void withType(Class<T> type, BiConsumer<Ctx, T> callable) {
                if (type.isAssignableFrom(node.getClass())) {
                    callable.accept(this, (T) node);
                }
            }
        }
    }

    static class Ansi {
        static String esc = "\u001B";
        static String reset = esc + "[0m";

        static int rgb(int r, int g, int b) {
            return 16 + b + 6 * g + 36 * r;
        }

        static String ergb(int r, int g, int b) {
            return esc + "[38;5;" + rgb(5, 2, 0) + "m";
        }

        static String fg(int val) {
            return esc + "[" + val + "m";
        }

        static Map<String, String> cols = new HashMap<>();

        static {
            cols.put("red", fg(31));
            cols.put("orange", ergb(5, 2, 0));
            cols.put("indigo", ergb(2, 0, 2));
            cols.put("violet", ergb(4, 0, 5));
            cols.put("green", fg(32));
            cols.put("yellow", fg(33));
            cols.put("blue", fg(34));
            cols.put("magenta", fg(35));
            cols.put("cyan", fg(36));
            cols.put("white", fg(37));
        }


        static String colorize(String text, String color) {
            StringBuilder sb = new StringBuilder();
            if (null == cols.get(color)) {
                return text;
            }
            sb.append(cols.get(color));

            sb.append(text);
            sb.append(reset);
            return sb.toString();
        }

        static String beginColor(String color) {
            if (null == cols.get(color)) {
                return "";
            }
            return cols.get(color);
        }
    }
}
