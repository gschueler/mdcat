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
            for (String envKey : System.getenv().keySet()) {

                if (envKey.startsWith("COL_")) {
                    String colName = envKey.substring("COL_".length()).toLowerCase();
                    colors.put(colName, System.getenv(envKey));
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
        map.put("imagehref", "blue");
        map.put("title", "green");
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
            ctx.textColor = getColor("bullet");
            ctx.setPrefixer(() -> ctx.nextListItemIndex() + ". ");
            ctxtStack.push(ctx);
            renderListBlock(orderedList);
            ctxtStack.pop();
        }

        @Override
        public void visit(final BulletList bulletList) {
            Ctx ctx = new Ctx(bulletList);
            ctx.textColor = getColor("bullet");
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

            ctx.textColor = getColor("header");
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
            html().text(Ansi.beginColor(getColor("code")));
            html().text("`");
            html().text(code.getLiteral());
            html().text("`");
            html().text(Ansi.reset);
        }

        @Override
        public void visit(final Emphasis emphasis) {
            Ctx ctx = new Ctx(emphasis);
            ctx.textColor = getColor("emphasis");
            ctxtStack.push(ctx);
            emitColorized(getColor("emphasis"), emphasis.getOpeningDelimiter());
            visitChildren(emphasis);
            emitColorized(getColor("emphasis"), emphasis.getClosingDelimiter());
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
            ctx.textColor = getColor("strong");
            ctxtStack.push(ctx);
            emitColorized(getColor("strong"), strongEmphasis.getOpeningDelimiter());
            visitChildren(strongEmphasis);
            emitColorized(getColor("strong"), strongEmphasis.getClosingDelimiter());
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
            html().text(Ansi.colorize(url, getColor("linkHref", "href")));
            if (link.getTitle() != null) {
                html().text(Ansi.beginColor(getColor("linkTitle", "title")));
                html().raw(" \"");
                html().text(link.getTitle());
                html().raw("\"");
                html().raw(Ansi.reset);
            }
            html().text(")");

            ctxtStack.pop();
        }

        @Override
        public void visit(final Image image) {
            String url = context.encodeUrl(image.getDestination());
            html().text("![");

            Ctx ctx = new Ctx(image);
            ctxtStack.push(ctx);
            visitChildren(image);
            html().text("](");
            html().text(Ansi.colorize(url, getColor("imageHref", "href")));
            if (image.getTitle() != null) {
                html().text(Ansi.beginColor(getColor("imageTitle", "title")));
                html().raw(" \"");
                html().text(image.getTitle());
                html().raw("\"");
                html().raw(Ansi.reset);
            }
            html().text(")");

            ctxtStack.pop();
        }


        private String getColor(final String... colorsarr) {
            for (String color : colorsarr) {
                if (null != colors.get(color.toLowerCase())) {
                    return colors.get(color.toLowerCase());
                }
            }
            return null;
        }

        @Override
        public void visit(final BlockQuote blockQuote) {
            Ctx ctx = new Ctx(blockQuote);
            ctxtStack.push(ctx);
            ctx.setTextColor(getColor("blockquote"));
            ctx.setPrefix("> ");
            visitChildren(blockQuote);

            ctxtStack.pop();
        }

        @Override
        public void visit(final IndentedCodeBlock indentedCodeBlock) {
            emitColorized(getColor("code"), indent("    ", indentedCodeBlock));
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
            html().text(Ansi.beginColor(getColor("code")));
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
                    : getColor("text");

            html().text(Ansi.beginColor(textcolor));

            if (ctxtStack.size() > 0) {
                ctxtStack.peek().withType(Node.class, (ctx, node) -> {
                    String prefix = ctx.getPrefix();
                    if (null != prefix) {
                        if(lastLine) {
                            html().raw(prefix);
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
            Supplier<String> prefixer;
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

            String getPrefix() {
                if (null != prefix) {
                    return prefix;
                }
                if (null != prefixer) {
                    return prefixer.get();
                }
                return null;
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

        static String esc(Integer... val) {
            StringBuilder sb = new StringBuilder();
            for (int i : val) {
                if (sb.length() > 0) {
                    sb.append(";");
                }
                sb.append(Integer.toString(i));
            }
            return escStart + sb.toString() + "m";
        }

        @Data
        static class Color {
            Integer[] mods;


            public Color(final Integer... mods) {
                this.mods = mods;
            }

            public Color mods(final Integer more) {
                Integer[] more1 = new Integer[1];
                more1[0] = more;
                return mods(more1);
            }

            public Color mods(final Integer[] more) {
                List<Integer> integers = new ArrayList<>(Arrays.asList(more));
                Integer[] modsar = mods;
                integers.addAll(Arrays.asList(mods));
                return new Color(integers.toArray(new Integer[0]));
            }

            public String toString() {
                return esc(mods);
            }
        }

        static Map<String, Color> cols = new HashMap<>();

        static {
            cols.put("black", new Color(30));
            cols.put("bg-black", new Color(40));
            cols.put("brightblack", new Color(90));
            cols.put("bg-brightblack", new Color(100));
            cols.put("red", new Color(31));
            cols.put("bg-red", new Color(41));
            cols.put("brightred", new Color(91));
            cols.put("bg-brightred", new Color(101));
            cols.put("orange", new Color(FG, 5, rgb(5, 2, 0)));
            cols.put("bg-orange", new Color(BG, 5, rgb(5, 2, 0)));
            cols.put("indigo", new Color(FG, 5, rgb(2, 0, 2)));
            cols.put("bg-indigo", new Color(BG, 5, rgb(2, 0, 2)));
            cols.put("violet", new Color(FG, 5, rgb(4, 0, 5)));
            cols.put("bg-violet", new Color(BG, 5, rgb(4, 0, 5)));
            cols.put("green", new Color(32));
            cols.put("bg-green", new Color(42));
            cols.put("brightgreen", new Color(92));
            cols.put("bg-brightgreen", new Color(102));
            cols.put("yellow", new Color(33));
            cols.put("bg-yellow", new Color(43));
            cols.put("brightyellow", new Color(93));
            cols.put("bg-brightyellow", new Color(103));
            cols.put("blue", new Color(34));
            cols.put("bg-blue", new Color(44));
            cols.put("brightblue", new Color(94));
            cols.put("bg-brightblue", new Color(104));
            cols.put("magenta", new Color(35));
            cols.put("bg-magenta", new Color(45));
            cols.put("brightmagenta", new Color(95));
            cols.put("bg-brightmagenta", new Color(105));
            cols.put("cyan", new Color(36));
            cols.put("bg-cyan", new Color(46));
            cols.put("brightcyan", new Color(96));
            cols.put("bg-brightcyan", new Color(106));
            cols.put("white", new Color(37));
            cols.put("bg-white", new Color(47));
            cols.put("brightwhite", new Color(97));
            cols.put("bg-brightwhite", new Color(107));

            cols.put("gray", new Color(FG, 5, rgb(1, 1, 1)));
            cols.put("bg-gray", new Color(BG, 5, rgb(1, 1, 1)));
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

        static Pattern colbase = Pattern.compile("(?<bold>bold-)?(?<color>[a-z]+)");
        static Pattern
                col16 =
                Pattern.compile("(?<bold>bold-)?(?<bg>bg-)?(?<r>\\d{1,2}),(?<g>\\d{1,2}),(?<b>\\d{1,2})");
        private static String getColor(final String color) {
            Color val = cols.get(color);
            if (val != null) {
                return val.toString();
            }
            Matcher matcher1 = colbase.matcher(color);
            if (matcher1.matches()) {
                String colgroup = matcher1.group("color");
                boolean bold = matcher1.group("bold") != null;
                Color color1 = cols.get(colgroup);
                if (color1 != null) {
                    return color1.mods(1).toString();
                }
            }
            //256 color
            Matcher matcher = col16.matcher(color);
            if (matcher.matches()) {
                if (matcher.group("bg") != null) {
                    return new Color(BG, 5, rgb(
                            Integer.parseInt(matcher.group("r")),
                            Integer.parseInt(matcher.group("g")),
                            Integer.parseInt(matcher.group("b"))
                    )).toString();
                }

                return new Color(FG, 5, rgb(
                        Integer.parseInt(matcher.group("r")),
                        Integer.parseInt(matcher.group("g")),
                        Integer.parseInt(matcher.group("b"))
                )).toString();

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
