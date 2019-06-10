package us.vario.greg.md;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.*;
import picocli.CommandLine;

import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandLine.Command(description = "Ansi colorized text rendering of Markdown files",
                     name = "md", mixinStandardHelpOptions = true, version = "0.1")
public class Main
        implements Callable<Void>
{

    public static final String DEFAULT_PROFILE = "light";
    @CommandLine.Parameters(index = "0", description = "The file to read.", paramLabel = "FILE")
    private File file;

    @CommandLine.Option(names = {"-H", "--html"}, description = "render as html")
    private boolean html;

    @CommandLine.Option(names = {"-p", "--plain"},
                        description = "render colorized text without markdown syntax (default)")
    private boolean plain;

    @CommandLine.Option(names = {"-m", "--markdown", "--md"},
                        description = "render colorized text *with* markdown syntax (inverts --plain), can be set "
                                      + "with env var MD_MD")
    private boolean markdown;

    @CommandLine.Option(names = {"-P", "--profile"},
                        description = "Use a prefdefined color profile: [light,dark], can be set with env var "
                                      + "MD_PROFILE")
    private String profile;

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
            Map<String, String> options = new HashMap<>();
            Map<String, String> colors = new HashMap<>(DEFAULT_COLORS);
            if (null == profile && System.getenv("MD_PROFILE") != null) {
                profile = System.getenv("MD_PROFILE").toLowerCase();
            }
            System.getenv().forEach((s, s2) -> {
                if (s.startsWith("MD_OPT_")) {
                    options.put(s.substring(7), s2);
                }
            });

            if (null == profile) {
                profile = DEFAULT_PROFILE;
            }
            if (null != profile) {
                colors.putAll(loadProfile(profile));
            }
            //eval env colors
            for (String envKey : System.getenv().keySet()) {

                if (envKey.startsWith("COL_")) {
                    String colName = envKey.substring("COL_".length()).toLowerCase();
                    colors.put(colName, System.getenv(envKey));
                }

            }
            HtmlRenderer renderer = HtmlRenderer.builder().
                    nodeRendererFactory(new MyCoreNodeRendererFactory(colors, options, isPlain())).build();
            renderer.render(document, System.out);

        }
        return null;
    }

    private boolean isPlain() {
        if (markdown) {
            return false;
        }
        if (plain) {
            return true;
        }
        return System.getenv("MD_MD") == null;
    }

    private Map<? extends String, ? extends String> loadProfile(final String profile) {
        HashMap<String, String> map = new HashMap<>();
        Properties props = new Properties();

        try (
                InputStream resourceAsStream = Main.class
                        .getClassLoader()
                        .getResourceAsStream("md-profile-" + profile.toLowerCase() + ".properties")
        ) {
            if (null != resourceAsStream) {
                props.load(resourceAsStream);
                for (Object o : props.keySet()) {
                    map.put(o.toString(), props.getProperty(o.toString()));
                }
            } else {
                throw new RuntimeException("no profile found: md-profile-" + profile.toLowerCase() + ".properties");
            }
        } catch (IOException ignored) {

        }

        return map;
    }

    static Map<String, String> DEFAULT_COLORS;
    static Map<String, String> DEFAULT_OPTS;

    public static final String DEFAULT_UNCHECKED_ITEM = "☐";

    public static final String DEFAULT_CHECKED_ITEM = "✓"; //☒ ✗

    static {
        HashMap<String, String> map = new HashMap<>();

        map.put("code", "red");
        map.put("strong", "orange");
        map.put("emphasis", "green");
        map.put("header", "brightblue");
        map.put("href", "orange");
        map.put("imagehref", "blue");
        map.put("title", "green");
        map.put("blockquote", "gray");
        map.put("imagetext", "brightblue");
        map.put("linktext", "brightblue");

        DEFAULT_COLORS = Collections.unmodifiableMap(map);

        HashMap<String, String> map2 = new HashMap<>();
        map2.put("UNCHECKED_ITEM", DEFAULT_UNCHECKED_ITEM);
        map2.put("CHECKED_ITEM", DEFAULT_CHECKED_ITEM);
        DEFAULT_OPTS = Collections.unmodifiableMap(map2);
    }

    @RequiredArgsConstructor
    private static class MyCoreNodeRendererFactory
            implements HtmlNodeRendererFactory
    {
        final Map<String, String> colors;
        final Map<String, String> options;
        final boolean plain;


        @Override
        public NodeRenderer create(final HtmlNodeRendererContext context) {
            return new MyCoreNodeRenderer(context, colors, options, plain);
        }
    }

    private static class MyCoreNodeRenderer
            extends CoreHtmlNodeRenderer
    {
        public static final String UNCHECKED_ITEM_TEXT = "[ ] ";
        public static final String CHECKED_ITEM_TEXT = "[x] ";
        final Map<String, String> colors;
        final Map<String, String> options;
        final boolean plain;

        public MyCoreNodeRenderer(
                final HtmlNodeRendererContext context,
                final Map<String, String> colors,
                final Map<String, String> options,
                boolean plain
        )
        {
            super(context);
            this.colors = colors;
            this.options = options;
            this.plain = plain;
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
            ctx.setPrefixer((text) -> ctx.nextListItemIndex() + ". ");
            ctxtStack.push(ctx);
            renderListBlock(orderedList);
            ctxtStack.pop();
        }

        @Override
        public void visit(final BulletList bulletList) {
            Ctx ctx = new Ctx(bulletList);
            ctx.textColor = getColor("bullet");
            if (plain) {
                ctx.setPrefixer((text) -> {
                    if (text.startsWith(UNCHECKED_ITEM_TEXT)) {
                        return options.getOrDefault("UNCHECKED_ITEM", DEFAULT_UNCHECKED_ITEM) + " ";
                    } else if (text.startsWith(CHECKED_ITEM_TEXT)) {
                        return options.getOrDefault("CHECKED_ITEM", DEFAULT_CHECKED_ITEM) + " ";
                    }
                    return "• ";
                });
                ctx.setTransform((text) -> {
                    if (text.startsWith(UNCHECKED_ITEM_TEXT) || text.startsWith(CHECKED_ITEM_TEXT)) {
                        return text.substring(4);
                    }
                    return text;
                });
            } else {
                ctx.setPrefix(bulletList.getBulletMarker() + " ");
            }
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
            Ctx ctx = new Ctx(heading);

            ctx.textColor = getColor("header");

            if (!plain) {
                StringBuilder h = new StringBuilder();
                for (int i = 0; i < heading.getLevel(); i++) {
                    h.append("#");
                }
                ctx.setPrefix(h.toString() + " ");
            }
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
            if (!plain) {
                html().text("`");
            }
            html().raw(code.getLiteral());
            if (!plain) {
                html().text("`");
            }
            html().text(Ansi.reset);
        }

        @Override
        public void visit(final Emphasis emphasis) {
            Ctx ctx = new Ctx(emphasis);
            ctx.textColor = getColor("emphasis");
            ctxtStack.push(ctx);
            if (!plain) {
                emitColorized(getColor("emphasis"), emphasis.getOpeningDelimiter());
            }
            visitChildren(emphasis);
            if (!plain) {
                emitColorized(getColor("emphasis"), emphasis.getClosingDelimiter());
            }
            ctxtStack.pop();
        }


        private void endDelimited(final Delimited emphasis) {
            html().text(emphasis.getClosingDelimiter());
            html().text(Ansi.reset);
        }

        private void emitColorized(final String color, final String text) {
            emitColorized(color, text, false);
        }

        private void emitColorized(final String color, final String text, boolean raw) {
            html().text(Ansi.beginColor(color));
            if (raw) {
                html().raw(text);
            } else {
                html().text(text);
            }
            html().text(Ansi.reset);
        }

        @Override
        public void visit(final StrongEmphasis strongEmphasis) {
            Ctx ctx = new Ctx(strongEmphasis);
            ctx.textColor = getColor("strong");
            ctxtStack.push(ctx);
            if (!plain) {
                emitColorized(getColor("strong"), strongEmphasis.getOpeningDelimiter());
            }
            visitChildren(strongEmphasis);
            if (!plain) {
                emitColorized(getColor("strong"), strongEmphasis.getClosingDelimiter());
            }
            ctxtStack.pop();
        }

        @Override
        public void visit(final Link link) {
            String url = context.encodeUrl(link.getDestination());
            if (!plain) {
                html().text("[");
            }

            Ctx ctx = new Ctx(link);
            ctx.setTextColor(getColor("linkText", "text"));
            ctxtStack.push(ctx);
            visitChildren(link);
            if (!plain) {
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
            }

            ctxtStack.pop();
        }

        @Override
        public void visit(final Image image) {
            String url = context.encodeUrl(image.getDestination());
            if (!plain) {
                html().text("![");
            }
            Ctx ctx = new Ctx(image);
            ctx.setTextColor(getColor("imageText", "text"));
            ctxtStack.push(ctx);

            visitChildren(image);
            if (!plain) {
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
            }

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
            if (!plain) {
                ctx.setPrefix("> ");
            }
            visitChildren(blockQuote);

            ctxtStack.pop();
        }

        @Override
        public void visit(final IndentedCodeBlock indentedCodeBlock) {
            if (!plain) {
                emitColorized(getColor("code"), indent("    ", indentedCodeBlock), true);
            } else {
                emitColorized(getColor("code"), indentedCodeBlock.getLiteral(), true);
            }
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
            if (!plain) {
                html().text(fence.toString());
                line();
            }
            html().raw(fencedCodeBlock.getLiteral());
            line();
            if (!plain) {
                html().text(fence.toString());
            }
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

            if (textcolor != null) {
                html().text(Ansi.beginColor(textcolor));
            }

            String literal = text.getLiteral();
            if (ctxtStack.size() > 0) {
                ctxtStack.peek().withType(Node.class, (ctx, node) -> {
                    String prefix = ctx.getPrefix(literal);
                    if (null != prefix) {
                        if(lastLine) {
                            html().raw(prefix);
                        }
                    }
                    html().raw(ctx.transform(literal));
                });
            } else {
                html().raw(literal);
            }
            if (text.getLiteral().length() > 0) {
                lastLine = text.getLiteral().charAt(text.getLiteral().length() - 1) == '\n';
            }

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
            Function<String, String> prefixer;
            Function<String, String> transform;
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

            String transform(String text) {
                if (null != transform) {
                    return transform.apply(text);
                }
                return text;
            }

            String getPrefix(String text) {
                if (null != prefix) {
                    return prefix;
                }
                if (null != prefixer) {
                    return prefixer.apply(text);
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
