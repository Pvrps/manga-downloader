package ca.purps.mangadownloader.downloader;

import java.nio.file.Path;

import ca.purps.mangadownloader.model.MangaEntity;

public interface Downloader {

    Path download(MangaEntity series);

}