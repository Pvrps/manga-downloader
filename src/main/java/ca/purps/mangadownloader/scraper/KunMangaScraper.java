package ca.purps.mangadownloader.scraper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import ca.purps.mangadownloader.config.AppConfig;
import ca.purps.mangadownloader.exception.ScraperException;
import ca.purps.mangadownloader.model.Chapter;
import ca.purps.mangadownloader.model.Series;
import ca.purps.mangadownloader.model.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
@RequiredArgsConstructor
public class KunMangaScraper implements MangaScraper {

    private final AppConfig config;
    private final OkHttpClient httpClient;

    public static final String BASE_URL = "https://kunmanga.com/";

    private static final Pattern CHAPTER_NAME_PATTERN = Pattern.compile("\"chapter\":\"(chapter-[\\d\\-]+)\"");
    private static final Pattern CHAPTER_ID_PATTERN = Pattern.compile("\"chapter\":\"chapter-([\\d\\-]+)\"");

    @Override
    public Series scrapeSeries(String url) {
        KunMangaScraper.log.info("Scraping series from URL: {}", url);
        Document doc = fetchPage(url);

        KunMangaScraper.log.debug("Extracting chapters from series page");

        String coverUrl = findLargestResolution(extractSrcsetUrls(doc, "div.summary_image img"));

        List<Chapter> chapters = new ArrayList<>();
        Series series = Series.builder()
                .url(url)
                .id(0)
                .title(extractMetaTag(doc, "og:title"))
                .description(extractMetaTag(doc, "og:description"))
                .authors(extractElements(doc, "div.author-content a"))
                .genres(extractElements(doc, "div.genres-content a"))
                .coverBytes(downloadCoverBytes(makeAbsoluteUrl(coverUrl)))
                .status(extractStatus(doc))
                .chapters(chapters)
                .build();

        List<String> chapterUrls = extractChapterUrls(doc);

        chapters.addAll(IntStream.range(0, chapterUrls.size())
                .mapToObj(i -> {
                    String chapterUrl = makeAbsoluteUrl(chapterUrls.get(chapterUrls.size() - 1 - i));
                    KunMangaScraper.log.debug("Found chapter URL: {}", chapterUrl);
                    Chapter chapter = scrapeChapter(series, chapterUrl, i);
                    return chapter;
                })
                .collect(Collectors.toList()));

        KunMangaScraper.log.info("Found {} chapters for series", chapters.size());

        KunMangaScraper.log.info("Successfully scraped series: {}", series.getTitle());
        return series;
    }

    @Override
    public Chapter scrapeChapter(String url) {
        Series series = scrapeSeries(url.replaceAll("/chapter-\\d+/?$", ""));
        return series.getChapters()
                .stream()
                .filter(chapter -> chapter.getUrl().equals(url))
                .findFirst()
                .orElseThrow(() -> new ScraperException("Chapter not found in series"));
    }

    private Chapter scrapeChapter(Series series, String url, int index) {
        KunMangaScraper.log.info("Scraping chapter from URL: {}", url);
        Document doc = fetchPage(url);

        String chapterName = extractChapterName(doc);
        List<String> imageUrls = extractImageUrls(doc, "wp-manga-chapter-img");

        KunMangaScraper.log.debug("Found {} images in chapter {}", imageUrls.size(), chapterName);
        return Chapter.builder()
                .series(series)
                .url(url)
                .id(extractChapterId(doc))
                .name(chapterName)
                .description(extractMetaTag(doc, "og:description"))
                .imageUrls(imageUrls)
                .seriesIndex(index)
                .build();
    }

    private String findLargestResolution(List<String> urls) {
        Pattern pattern = Pattern.compile(".*-(\\d+)x(\\d+)\\.[a-zA-Z]+$");
        String nonResolution = null;
        String largestResolutionUrl = null;
        int maxPixels = -1;

        for (String url : urls) {
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                int width = Integer.parseInt(matcher.group(1));
                int height = Integer.parseInt(matcher.group(2));
                int pixelCount = width * height;
                if (pixelCount > maxPixels) {
                    maxPixels = pixelCount;
                    largestResolutionUrl = url;
                }
            } else if (nonResolution == null) {
                nonResolution = url; // First non-resolution file
            }
        }

        return (nonResolution != null) ? nonResolution : largestResolutionUrl;
    }

    private String makeAbsoluteUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        return KunMangaScraper.BASE_URL + (url.startsWith("/") ? url : "/" + url);
    }

    private Document fetchPage(String url) {
        KunMangaScraper.log.debug("Fetching page: {}", url);

        try {
            String absoluteUrl = makeAbsoluteUrl(url);
            KunMangaScraper.log.debug("Making request to: {}", absoluteUrl);

            Request request = new Request.Builder()
                    .url(absoluteUrl)
                    .header("User-Agent", config.getUserAgent())
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new ScraperException(String.format("Failed to fetch page: {} (Status code: {})", url, response.code()));
                }
                KunMangaScraper.log.debug("Successfully fetched page: {} (Status code: {})", url, response.code());
                return Jsoup.parse(response.body().string(), url);
            }
        } catch (IOException e) {
            throw new ScraperException(String.format("Error fetching page: %s", url), e);
        }
    }

    private List<String> extractChapterUrls(Document doc) {
        return doc.select("ul.version-chap li.wp-manga-chapter a")
                .stream()
                .map(e -> e.attr("href").trim())
                .collect(Collectors.toList());
    }

    private String extractMetaTag(Document doc, String property) {
        return doc.select("meta[property=" + property + "]").attr("content").trim();
    }

    private List<String> extractElements(Document doc, String selector) {
        return doc.select(selector)
                .stream()
                .map(e -> e.text().trim())
                .filter(text -> !text.isEmpty())
                .collect(Collectors.toList());
    }

    private String extractAttribute(Document doc, String selector, String attr) {
        return doc.select(selector).attr(attr).trim();
    }

    private List<String> extractSrcsetUrls(Document doc, String selector) {
        String srcset = extractAttribute(doc, selector, "srcset");

        return Arrays.stream(srcset.split(","))
                .map(entry -> entry.trim().split(" ")[0])
                .collect(Collectors.toList());
    }

    private Status extractStatus(Document doc) {
        Element statusHeading = doc.selectFirst("div.summary-heading h5");

        if (statusHeading != null && statusHeading.text().trim().equalsIgnoreCase("Status")) {
            Element statusContent = statusHeading.parent().nextElementSibling();

            if (statusContent != null && statusContent.hasClass("summary-content")) {
                return switch (statusContent.text().toLowerCase().trim()) {
                    case "ongoing" -> Status.ONGOING;
                    case "completed" -> Status.COMPLETED;
                    case "hiatus" -> Status.HIATUS;
                    case "cancelled" -> Status.CANCELLED;
                    default -> Status.UNKNOWN;
                };

            }
        }

        return Status.UNKNOWN;
    }

    private String extractChapterId(Document doc) {
        return extractScript(doc, "query_vars")
                .flatMap(script -> KunMangaScraper.CHAPTER_ID_PATTERN.matcher(script)
                        .results()
                        .findFirst()
                        .map(match -> match.group(1).trim()))
                .orElse(null);
    }

    private String extractChapterName(Document doc) {
        return extractScript(doc, "query_vars")
                .flatMap(script -> KunMangaScraper.CHAPTER_NAME_PATTERN.matcher(script)
                        .results()
                        .findFirst()
                        .map(match -> match.group(1).trim()))
                .orElse("Unknown Chapter");
    }

    private List<String> extractImageUrls(Document doc, String className) {
        return doc.select("img." + className)
                .stream()
                .map(imgElement -> imgElement.attr("src").trim())
                .collect(Collectors.toList());
    }

    private Optional<String> extractScript(Document doc, String data) {
        return Optional.ofNullable(doc.select("script:containsData(" + data + ")").html());
    }

    private byte[] downloadCoverBytes(String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty()) {
            return null;
        }

        try {
            Request request = new Request.Builder()
                    .url(coverUrl)
                    .header("User-Agent", config.getUserAgent())
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    KunMangaScraper.log.warn("Failed to download cover image: {} (Status code: {})", coverUrl, response.code());
                    return null;
                }
                return response.body().bytes();
            }
        } catch (IOException e) {
            KunMangaScraper.log.error("Error downloading cover image: {}", coverUrl, e);
            return null;
        }
    }

}
