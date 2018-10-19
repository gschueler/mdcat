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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            Map<String, String> colors = new HashMap<>(DEFAULT_COLORS);
            //eval env colors
            for (String s : colors.keySet()) {
                String envName = ("COL_" + s).toUpperCase();
                if (System.getenv(envName) != null) {
                    colors.put(s, System.getenv(envName));
                }
            }
            HtmlRenderer renderer = HtmlRenderer.builder().
                    nodeRendererFactory(new MyCoreNodeRendererFactory(colors)).build();
            renderer.render(document, System.out);

        }
        return null;
    }

    static Map<String, String> DEFAULT_COLORS;

    static {
        HashMap<String, String> map = new HashMap<>();

        map.put("code", "red");
        map.put("emphasis", "brightyellow");
        map.put("strong", "orange");
        map.put("header", "brightblue");
        map.put("bullet", "5,4,3");
        map.put("href", "orange");
        map.put("linkTitle", "green");
        map.put("blockquote", "gray");
        map.put("text", "white");

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
        public void visit(final Document document) {
            super.visit(document);
            line();
        }

        @Override
        public void visit(final OrderedList orderedList) {
            Ctx ctx = new Ctx(orderedList, orderedList.getStartNumber());
            ctx.textColor = colors.get("bullet");
            ctx.setPrefix(ctx.nextListItemIndex() + ". ");
            ctxtStack.push(ctx);
            renderListBlock(orderedList);
            ctxtStack.pop();
        }

        @Override
        public void visit(final BulletList bulletList) {
            Ctx ctx = new Ctx(bulletList);
            ctx.textColor = colors.get("bullet");
            ctx.setPrefix(bulletList.getBulletMarker() + " ");
            ctxtStack.push(ctx);
            renderListBlock(bulletList);
            ctxtStack.pop();
        }

        private void renderListBlock(ListBlock listBlock) {
            line();
            visitChildren(listBlock);
            html().text("\n");
        }

        @Override
        public void visit(final ListItem listItem) {
            visitChildren(listItem);
            line();
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

            line();
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < heading.getLevel(); i++) {
                h.append("#");
            }
            Ctx ctx = new Ctx(heading);

            ctx.textColor = colors.get("header");
            ctx.prefix = h.toString() + " ";
            ctxtStack.push(ctx);

            visitChildren(heading);

            html().text("\n\n");
            ctxtStack.pop();
        }

        @Override
        public void visit(final Paragraph paragraph) {
            boolean inTightList = isInTightList(paragraph);
            if (!inTightList) {
                line();
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
            Ctx ctx = new Ctx(emphasis);
            ctx.textColor = colors.get("emphasis");
            ctxtStack.push(ctx);
            emitColorized(colors.get("emphasis"), emphasis.getOpeningDelimiter());
            visitChildren(emphasis);
            emitColorized(colors.get("emphasis"), emphasis.getClosingDelimiter());
            ctxtStack.pop();
        }


        private void endDelimited(final Delimited emphasis) {
            html().text(emphasis.getClosingDelimiter());
            html().text(Ansi.reset);
        }

        private void emitColorized(final String color, final String text) {
            html().text(Ansi.beginColor(color));
            html().text(text);
            html().text(Ansi.reset);
        }

        @Override
        public void visit(final StrongEmphasis strongEmphasis) {
            Ctx ctx = new Ctx(strongEmphasis);
            ctx.textColor = colors.get("strong");
            ctxtStack.push(ctx);
            emitColorized(colors.get("strong"), strongEmphasis.getOpeningDelimiter());
            visitChildren(strongEmphasis);
            emitColorized(colors.get("strong"), strongEmphasis.getClosingDelimiter());
            ctxtStack.pop();
        }

        @Override
        public void visit(final Link link) {
            String url = context.encodeUrl(link.getDestination());
            html().text("[");

            Ctx ctx = new Ctx(link);
            ctxtStack.push(ctx);
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

            ctxtStack.pop();
        }

        @Override
        public void visit(final BlockQuote blockQuote) {
            Ctx ctx = new Ctx(blockQuote);
            ctxtStack.push(ctx);
            ctx.setTextColor(colors.get("blockquote"));
            ctx.setPrefix("> ");
            visitChildren(blockQuote);

            ctxtStack.pop();
        }

        @Override
        public void visit(final IndentedCodeBlock indentedCodeBlock) {
            emitColorized(colors.get("code"), indent("    ", indentedCodeBlock));
        }

        private String indent(String indent, final IndentedCodeBlock indentedCodeBlock) {

            String[] split = indentedCodeBlock.getLiteral().split("[\r\n]");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < split.length; i++) {
                String s = split[i];
                sb.append(indent);
                sb.append(s);
                sb.append("\n");
            }
            return sb.toString();
        }

        @Override
        public void visit(final FencedCodeBlock fencedCodeBlock) {
            html().text(Ansi.beginColor(colors.get("code")));
            StringBuilder fence = new StringBuilder();
            for (int i = 0; i < fencedCodeBlock.getFenceLength(); i++) {
                fence.append(fencedCodeBlock.getFenceChar());
            }
            html().text(fence.toString());
            line();
            html().text(fencedCodeBlock.getLiteral());
            line();
            html().text(fence.toString());
            html().text(Ansi.reset);
        }
        boolean lastLine=false;
        private void line() {
            html().line();
            lastLine=true;
        }

        @Override
        public void visit(final SoftLineBreak softLineBreak) {
            super.visit(softLineBreak);
            lastLine=true;
        }

        @Override
        public void visit(final HardLineBreak hardLineBreak) {
            html().raw(context.getSoftbreak());
            lastLine=true;
        }

        @Override
        public void visit(final Text text) {
            String
                    textcolor =
                    ctxtStack.size() > 0 && ctxtStack.peek().textColor != null
                    ? ctxtStack.peek().textColor
                    : colors.get("text");

            html().text(Ansi.beginColor(textcolor));

            if (ctxtStack.size() > 0) {
                ctxtStack.peek().withType(Node.class, (ctx, node) -> {
                    if (null != ctx.prefix) {
                        if(lastLine) {
                            html().raw(ctx.prefix);
                        }
                    }
                });
            }

            super.visit(text);
            lastLine = text.getLiteral().charAt(text.getLiteral().length() - 1) == '\n';

            if (textcolor != null) {
                html().text(Ansi.reset);
            }
        }

        private HtmlWriter html() {
            return context.getWriter();
        }

        @Data
        private class Ctx {
            final Node node;
            String prefix;
            String textColor;

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
        static String escStart = esc + "[";
        static final int FG = 38;
        static final int BG = 48;
        static String reset = esc + "[0m";
        static String modeBold = "bold-";
        static String modeBg = "bg-";

        static int rgb(int r, int g, int b) {
            return 16 + b + 6 * g + 36 * r;
        }

        static String fgrgb(int r, int g, int b) {
            return esc(FG, 5, rgb(r, g, b));
        }

        static String bgrgb(int r, int g, int b) {
            return esc(BG, 5, rgb(r, g, b));
        }

        static String basic(int val) {
            return escStart + val + "m";
        }

        static String esc(int... val) {
            StringBuilder sb = new StringBuilder();
            for (int i : val) {
                if (sb.length() > 0) {
                    sb.append(";");
                }
                sb.append(Integer.toString(i));
            }
            return escStart + sb.toString() + "m";
        }

        static Map<String, String> cols = new HashMap<>();

        static {
            cols.put("black", basic(30));
            cols.put("bg-black", basic(40));
            cols.put("brightblack", basic(90));
            cols.put("bg-brightblack", basic(100));
            cols.put("red", basic(31));
            cols.put("bg-red", basic(41));
            cols.put("brightred", basic(91));
            cols.put("bg-brightred", basic(101));
            cols.put("orange", fgrgb(5, 2, 0));
            cols.put("bg-orange", bgrgb(5, 2, 0));
            cols.put("indigo", fgrgb(2, 0, 2));
            cols.put("bg-indigo", bgrgb(2, 0, 2));
            cols.put("violet", fgrgb(4, 0, 5));
            cols.put("bg-violet", bgrgb(4, 0, 5));
            cols.put("green", basic(32));
            cols.put("bg-green", basic(42));
            cols.put("brightgreen", basic(92));
            cols.put("bg-brightgreen", basic(102));
            cols.put("yellow", basic(33));
            cols.put("bg-yellow", basic(43));
            cols.put("brightyellow", basic(93));
            cols.put("bg-brightyellow", basic(103));
            cols.put("blue", basic(34));
            cols.put("bg-blue", basic(44));
            cols.put("brightblue", basic(94));
            cols.put("bg-brightblue", basic(104));
            cols.put("magenta", basic(35));
            cols.put("bg-magenta", basic(45));
            cols.put("brightmagenta", basic(95));
            cols.put("bg-brightmagenta", basic(105));
            cols.put("cyan", basic(36));
            cols.put("bg-cyan", basic(46));
            cols.put("brightcyan", basic(96));
            cols.put("bg-brightcyan", basic(106));
            cols.put("white", basic(37));
            cols.put("bg-white", basic(47));
            cols.put("brightwhite", basic(97));
            cols.put("bg-brightwhite", basic(107));

            cols.put("gray", fgrgb(1, 1, 1));
            cols.put("bg-gray", bgrgb(1, 1, 1));
        }


        static String colorize(String text, String color) {
            StringBuilder sb = new StringBuilder();
            if (null == getColor(color)) {
                return text;
            }
            sb.append(getColor(color));

            sb.append(text);
            sb.append(reset);
            return sb.toString();
        }

        private static String getColor(final String color) {
            String val = cols.get(color);
            if (val != null) {
                return val;
            }
            //256 color
            Pattern compile = Pattern.compile("(?<bg>bg-)?(?<r>\\d{1,2}),(?<g>\\d{1,2}),(?<b>\\d{1,2})");
            Matcher matcher = compile.matcher(color);
            if (matcher.matches()) {
                if (matcher.group("bg") != null) {
                    return bgrgb(
                            Integer.parseInt(matcher.group("r")),
                            Integer.parseInt(matcher.group("g")),
                            Integer.parseInt(matcher.group("b"))
                    );
                }
                return fgrgb(
                        Integer.parseInt(matcher.group("r")),
                        Integer.parseInt(matcher.group("g")),
                        Integer.parseInt(matcher.group("b"))
                );

            }
            return null;
        }

        static String beginColor(String color) {
            if (null == getColor(color)) {
                return "";
            }
            return getColor(color);
        }
    }
}
