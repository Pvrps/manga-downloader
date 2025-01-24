package ca.purps.mangadownloader.scraper;

import ca.purps.mangadownloader.model.Chapter;
import ca.purps.mangadownloader.model.Series;

public interface MangaScraper {

    Series scrapeSeries(String url);

    Chapter scrapeChapter(String url);

}
