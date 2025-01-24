package ca.purps.mangadownloader.tracker;

import ca.purps.mangadownloader.model.Chapter;
import ca.purps.mangadownloader.model.Series;

public interface Tracker {

    public void markChapterDownloaded(Chapter chapter);

    public boolean isSeriesDownloaded(Series series);

    public boolean isChapterDownloaded(Chapter chapter);

}
