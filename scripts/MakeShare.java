import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Makes the link-preview assets for one post, so a pasted link shows a card on X and LinkedIn.
 *
 *   java scripts/MakeShare.java <slug> "<title>" "<description>" "<tag line>"
 *
 * Writes:
 *   posts/images/og-<slug>.png   a 1200x630 title card
 *   share/<slug>.html            a static page with Open Graph + Twitter Card tags that sends people on to
 *                                post.html?p=<slug>
 *
 * Why a separate page: crawlers read the raw HTML and do not run JavaScript, and post.html is one page for every
 * post, so only a per-post static page can carry per-post tags. Share the link printed at the end.
 */
public class MakeShare {
    static final String SITE = "https://dhawanrachakonda.github.io";

    public static void main(String[] a) throws Exception {
        if (a.length != 4) {
            System.err.println("usage: java scripts/MakeShare.java <slug> \"<title>\" \"<description>\" \"<tag line>\"");
            System.exit(2);
        }
        String slug = a[0], title = a[1], desc = a[2], tag = a[3];
        if (!slug.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            System.err.println("slug must be lowercase letters, digits and hyphens");
            System.exit(2);
        }
        if (!Files.exists(Path.of("post.html"))) {
            System.err.println("run this from the repository root");
            System.exit(2);
        }
        if (!Files.exists(Path.of("posts", slug + ".md"))) {
            System.err.println("posts/" + slug + ".md does not exist yet; write the post first");
            System.exit(2);
        }

        System.setProperty("java.awt.headless", "true");
        File png = new File("posts/images/og-" + slug + ".png");
        drawCard(png, title, tag);

        Files.createDirectories(Path.of("share"));
        Path html = Path.of("share", slug + ".html");
        Files.writeString(html, page(slug, title, desc), StandardCharsets.UTF_8);

        System.out.println("wrote " + png.getPath());
        System.out.println("wrote " + html);
        System.out.println("share this link: " + SITE + "/share/" + slug + ".html");
    }

    static void drawCard(File out, String title, String tag) throws Exception {
        int w = 1200, h = 630;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(new Color(0xFAFAF8));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0xC2410C));
        g.fillRect(0, 0, 16, h);

        int x = 80, maxW = w - x - 80;
        g.setColor(new Color(0xC2410C));
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.drawString(tag, x, 110);

        // shrink the title until it fits in at most 4 lines
        int size = 70;
        List<String> lines;
        FontMetrics fm;
        while (true) {
            g.setFont(new Font("SansSerif", Font.BOLD, size));
            fm = g.getFontMetrics();
            lines = wrap(title, fm, maxW);
            if (lines.size() <= 4 || size <= 36) break;
            size -= 4;
        }
        g.setColor(new Color(0x1A1A1A));
        int y = 210;
        for (String line : lines) {
            g.drawString(line, x, y);
            y += (int) (size * 1.2);
        }

        g.setColor(new Color(0x555555));
        g.setFont(new Font("SansSerif", Font.PLAIN, 32));
        g.drawString("Dhawan Rachakonda  |  Learn. Implement. Mentor.", x, h - 70);
        g.dispose();
        ImageIO.write(img, "png", out);
    }

    static List<String> wrap(String text, FontMetrics fm, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = cur.length() == 0 ? word : cur + " " + word;
            if (fm.stringWidth(next) > maxW && cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(next);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String page(String slug, String title, String desc) {
        String share = SITE + "/share/" + slug + ".html";
        String post = SITE + "/post.html?p=" + slug;
        String image = SITE + "/posts/images/og-" + slug + ".png";
        String t = esc(title), d = esc(desc);
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>%1$s | Dhawan Rachakonda</title>
              <meta name="description" content="%2$s">
              <link rel="canonical" href="%3$s">
              <meta property="og:type" content="article">
              <meta property="og:site_name" content="Dhawan Rachakonda">
              <meta property="og:title" content="%1$s">
              <meta property="og:description" content="%2$s">
              <meta property="og:url" content="%3$s">
              <meta property="og:image" content="%5$s">
              <meta property="og:image:width" content="1200">
              <meta property="og:image:height" content="630">
              <meta name="twitter:card" content="summary_large_image">
              <meta name="twitter:title" content="%1$s">
              <meta name="twitter:description" content="%2$s">
              <meta name="twitter:image" content="%5$s">
              <script>location.replace(%4$s);</script>
            </head>
            <body>
              <p><a href="%6$s">Read the post: %1$s</a></p>
            </body>
            </html>
            """.formatted(t, d, share, "\"" + post + "\"", image, post);
    }
}
