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
public class BatotoScraper implements MangaScraper {

    private final AppConfig config;
    private final OkHttpClient httpClient;

    private static final String BASE_URL = "https://bato.to";

    private static final Pattern SERIES_ID_PATTERN = Pattern.compile("subjectIid\\s*=\\s*(\\d+);");

    private static final Pattern CHAPTER_NAME_PATTERN = Pattern.compile("local_text_epi\\s*=\\s*'([^']+)'");
    private static final Pattern CHAPTER_ID_PATTERN = Pattern.compile("episodeIid\\s*=\\s*(\\d+);");

    private static final Pattern IMAGE_VARIABLE_PATTERN = Pattern.compile("imgHttps\\s*=\\s*\\[(.*?)\\];");
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile("\"(https://[^\"]+)\"");

    @Override
    public Series scrapeSeries(String url) {
        BatotoScraper.log.info("Scraping series from URL: {}", url);
        Document doc = fetchPage(url);

        BatotoScraper.log.debug("Extracting chapters from series page");

        List<Chapter> chapters = new ArrayList<>();
        Series series = Series.builder()
                .url(url)
                .id(extractSeriesId(doc))
                .title(extractMetaTag(doc, "og:title"))
                .description(extractMetaTag(doc, "description"))
                .authors(extractElements(doc, "div.attr-item:has(b:contains(Authors:)) span a"))
                .genres(extractElements(doc, "div.attr-item:has(b:contains(Genres:)) span *"))
                .coverBytes(downloadCoverBytes(makeAbsoluteUrl(extractAttribute(doc, "div.attr-cover > img.shadow-6", "src"))))
                .status(extractStatus(doc))
                .chapters(chapters)
                .build();

        List<String> chapterUrls = extractChapterUrls(doc);

        chapters.addAll(IntStream.range(0, chapterUrls.size())
                .mapToObj(i -> {
                    String chapterUrl = makeAbsoluteUrl(chapterUrls.get(chapterUrls.size() - 1 - i));
                    BatotoScraper.log.debug("Found chapter URL: {}", chapterUrl);
                    Chapter chapter = scrapeChapter(series, chapterUrl, i);
                    return chapter;
                })
                .collect(Collectors.toList()));

        BatotoScraper.log.info("Found {} chapters for series", chapters.size());

        BatotoScraper.log.info("Successfully scraped series: {}", series.getTitle());
        return series;
    }


    @Override
    public Chapter scrapeChapter(String url) {
        Document doc = fetchPage(url);
        Series series = scrapeSeries(extractHref(doc, "h3.nav-title a"));
        return series.getChapters()
                .stream()
                .filter(chapter -> chapter.getUrl().equals(url))
                .findFirst()
                .orElseThrow(() -> new ScraperException("Chapter not found in series"));
    }

    private Chapter scrapeChapter(Series series, String url, int index) {
        BatotoScraper.log.info("Scraping chapter from URL: {}", url);
        Document doc = fetchPage(url);

        String chapterName = extractChapterName(doc);
        List<String> imageUrls = extractImageUrls(doc);

        BatotoScraper.log.debug("Found {} images in chapter {}", imageUrls.size(), chapterName);
        return Chapter.builder()
                .series(series)
                .url(url)
                .id(extractChapterId(doc))
                .name(chapterName)
                .description(extractMetaTag(doc, "description"))
                .imageUrls(imageUrls)
                .seriesIndex(index)
                .build();
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
        return BatotoScraper.BASE_URL + (url.startsWith("/") ? url : "/" + url);
    }

    private Document fetchPage(String url) {
        BatotoScraper.log.debug("Fetching page: {}", url);

        try {
            String absoluteUrl = makeAbsoluteUrl(url);
            BatotoScraper.log.debug("Making request to: {}", absoluteUrl);

            Request request = new Request.Builder()
                    .url(absoluteUrl)
                    .header("User-Agent", config.getUserAgent())
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new ScraperException(String.format("Failed to fetch page: {} (Status code: {})", url, response.code()));
                }
                BatotoScraper.log.debug("Successfully fetched page: {} (Status code: {})", url, response.code());
                return Jsoup.parse(response.body().string(), url);
            }
        } catch (IOException e) {
            throw new ScraperException(String.format("Error fetching page: %s", url), e);
        }
    }

    private List<String> extractChapterUrls(Document doc) {
        return doc.select("a.visited.chapt")
                .stream()
                .map(e -> e.attr("href"))
                .collect(Collectors.toList());
    }

    private String extractMetaTag(Document doc, String property) {
        return doc.select("meta[property=" + property + "]").attr("content");
    }

    private List<String> extractElements(Document doc, String selector) {
        return doc.select(selector)
                .stream()
                .map(Element::text)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.toList());
    }

    private String extractAttribute(Document doc, String selector, String attr) {
        return doc.select(selector).attr(attr);
    }

    private Status extractStatus(Document doc) {
        String status = doc.select("div.attr-item:has(b:contains(Status:)) span").text().toLowerCase();
        return switch (status) {
            case "ongoing" -> Status.ONGOING;
            case "completed" -> Status.COMPLETED;
            case "hiatus" -> Status.HIATUS;
            case "cancelled" -> Status.CANCELLED;
            default -> Status.UNKNOWN;
        };
    }

    private Integer extractSeriesId(Document doc) {
        return extractScript(doc, "subjectIid")
                .flatMap(script -> BatotoScraper.SERIES_ID_PATTERN.matcher(script)
                        .results()
                        .findFirst()
                        .map(match -> Integer.valueOf(match.group(1))))
                .orElse(null);
    }

    private Integer extractChapterId(Document doc) {
        return extractScript(doc, "episodeIid")
                .flatMap(script -> BatotoScraper.CHAPTER_ID_PATTERN.matcher(script)
                        .results()
                        .findFirst()
                        .map(match -> Integer.valueOf(match.group(1))))
                .orElse(null);
    }

    private String extractChapterName(Document doc) {
        return extractScript(doc, "local_text_epi")
                .flatMap(script -> BatotoScraper.CHAPTER_NAME_PATTERN.matcher(script)
                        .results()
                        .findFirst()
                        .map(match -> match.group(1)))
                .orElse("Unknown Chapter");
    }

    private String extractHref(Document doc, String selector) {
        return makeAbsoluteUrl(doc.select(selector).attr("href"));
    }

    private List<String> extractImageUrls(Document doc) {
        return extractScript(doc, "imgHttps")
                .flatMap(script -> Optional.of(BatotoScraper.IMAGE_VARIABLE_PATTERN.matcher(script)))
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1).split(","))
                .map(urls -> Arrays.stream(urls)
                        .map(BatotoScraper.IMAGE_URL_PATTERN::matcher)
                        .filter(Matcher::find)
                        .map(m -> m.group(1))
                        .collect(Collectors.toList()))
                .orElse(List.of());
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
                    BatotoScraper.log.warn("Failed to download cover image: {} (Status code: {})", coverUrl, response.code());
                    return null;
                }
                return response.body().bytes();
            }
        } catch (IOException e) {
            BatotoScraper.log.error("Error downloading cover image: {}", coverUrl, e);
            return null;
        }
    }

}