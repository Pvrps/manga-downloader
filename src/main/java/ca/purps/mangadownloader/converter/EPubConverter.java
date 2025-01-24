package ca.purps.mangadownloader.converter;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import ca.purps.mangadownloader.config.AppConfig;
import ca.purps.mangadownloader.exception.TrackerException;
import ca.purps.mangadownloader.model.Chapter;
import ca.purps.mangadownloader.utility.ProcessHelper;
import ca.purps.mangadownloader.utility.ProcessHelper.ProcessResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class EPubConverter {
    private static final String KCC_SCRIPT_PATH = Optional.ofNullable(System.getenv("KCC_SCRIPT")).orElse("");

    private final AppConfig config;

    public void convertFromCBZ(List<Chapter> chapters) {
        if (!config.isConvertToEpub() || !isKccAvailable()) {
            return;
        }

        chapters.forEach(this::convertFromCBZ);
    }

    private void convertFromCBZ(Chapter chapter) {
        Path archivePath = chapter.getArchivePath();
        try {
            Path epubPath = archivePath.resolveSibling(archivePath.getFileName().toString().replace(".cbz", ".epub"));

            ProcessResult result = ProcessHelper.run(config, String.format("python %s %s %s",
                    EPubConverter.KCC_SCRIPT_PATH,
                    archivePath.toString(),
                    config.getConversionArguments()));

            if (result.getExitCode() == 0) {
                EPubConverter.log.info("Successfully converted CBZ {} to EPUB: {}", archivePath, epubPath);

                rebuildEPub(epubPath, chapter);

                Files.delete(archivePath);
                chapter.setArchivePath(epubPath);
            } else {
                throw new TrackerException(String.format("Error converting CBZ %s to EPUB: %s", archivePath, result.getErrorOutput()));
            }
        } catch (IOException e) {
            throw new TrackerException(String.format("Error converting CBZ %s to EPUB: ", archivePath), e);
        }
    }

    private void rebuildEPub(Path epubPath, Chapter chapter) {
        String manifestContent = "";
        String spineContent = "";
        String opfEntryName = "";

        File tempMetadata = null;
        File tempEPub = null;

        try {
            try (ZipFile zipFile = new ZipFile(epubPath.toFile())) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".opf")) {
                        opfEntryName = entry.getName();
                        try (InputStream is = zipFile.getInputStream(entry)) {
                            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                            Document existingDoc = db.parse(is);

                            // Extract manifest and spine
                            Node manifestNode = existingDoc.getElementsByTagName("manifest").item(0);
                            Node spineNode = existingDoc.getElementsByTagName("spine").item(0);

                            if (manifestNode != null) {
                                manifestContent = nodeToString(manifestNode);
                            }
                            if (spineNode != null) {
                                spineContent = nodeToString(spineNode);
                            }

                            break;
                        }
                    }
                }
            }

            // Create new metadata document
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element packageElement = doc.createElement("package");
            doc.appendChild(packageElement);
            packageElement.setAttribute("xmlns", "http://www.idpf.org/2007/opf");
            packageElement.setAttribute("unique-identifier", "uuid_id");
            packageElement.setAttribute("version", "2.0");

            Element metadata = doc.createElement("metadata");
            metadata.setAttribute("xmlns:dc", "http://purl.org/dc/elements/1.1/");
            metadata.setAttribute("xmlns:opf", "http://www.idpf.org/2007/opf");
            packageElement.appendChild(metadata);

            // Add required DC elements
            addDcElement(doc, metadata, "identifier", UUID.randomUUID().toString(), Map.of("opf:scheme", "uuid", "id", "uuid_id"));
            addDcElement(doc, metadata, "title", chapter.getSeries().getId() + " - " + chapter.getName() + " (" + chapter.getId() + ")", null);

            chapter.getSeries().getAuthors().forEach(author -> {
                addDcElement(doc, metadata, "creator", author, Map.of("opf:file-as", author, "opf:role", "aut"));
            });

            addDcElement(doc, metadata, "date", "0101-01-01T00:00:00+00:00", null);
            addDcElement(doc, metadata, "description", chapter.getDescription(), null);
            addDcElement(doc, metadata, "language", "eng", null);

            addMetaElement(doc, metadata, "cover", "cover");

            // Add Calibre metadata
            addMetaElement(doc, metadata, "calibre:series", chapter.getSeries().getTitle());
            addMetaElement(doc, metadata, "calibre:series_index", String.valueOf(chapter.getSeriesIndex()));
            addMetaElement(doc, metadata, "calibre:timestamp", "0101-01-01T00:00:00+00:00");
            addMetaElement(doc, metadata, "calibre:title_sort", chapter.getName());

            // Append preserved content
            if (!manifestContent.isEmpty()) {
                appendXmlString(doc, packageElement, manifestContent);
            }
            if (!spineContent.isEmpty()) {
                appendXmlString(doc, packageElement, spineContent);
            }

            // Create temporary files
            tempMetadata = File.createTempFile("metadata", ".opf", epubPath.getParent().toFile());
            tempMetadata.deleteOnExit();
            tempEPub = File.createTempFile("epub", ".epub", epubPath.getParent().toFile());
            tempEPub.deleteOnExit();

            // Write new metadata
            TransformerFactory.newInstance()
                    .newTransformer()
                    .transform(new DOMSource(doc), new StreamResult(tempMetadata));

            // Update EPUB
            try (ZipFile zipFile = new ZipFile(epubPath.toFile());
                    ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempEPub))) {

                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    zos.putNextEntry(new ZipEntry(entry.getName()));

                    if (entry.getName().equals(opfEntryName)) {
                        Files.copy(tempMetadata.toPath(), zos);
                    } else if ((entry.getName().contains("cover.")) && chapter.getSeries().getCoverBytes() != null) {
                        String extension = entry.getName().substring(entry.getName().lastIndexOf('.') + 1);

                        // Modify cover image
                        byte[] coverBytes = chapter.getSeries().getCoverBytes();
                        BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(coverBytes));

                        Graphics2D g2d = originalImage.createGraphics();
                        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR);

                        // Initial font size relative to image height
                        int baseFontSize = originalImage.getHeight() / 10;
                        Font font = new Font("Arial", Font.BOLD, baseFontSize);
                        g2d.setFont(font);
                        FontMetrics fm = g2d.getFontMetrics();

                        // Black bar dimensions (fixed height)
                        int barHeight = fm.getHeight() * 2;
                        int barY = originalImage.getHeight() - barHeight;

                        // Fill the entire width with a black bar
                        g2d.setColor(new Color(0, 0, 0, 200));
                        g2d.fillRect(0, barY, originalImage.getWidth(), barHeight);

                        // Chapter name processing
                        String chapterName = chapter.getName();
                        boolean fontReduced = false;

                        // Only reduce the font size if necessary
                        while (fm.stringWidth(chapterName) > originalImage.getWidth() * 0.9) {
                            baseFontSize -= 2;
                            font = new Font("Arial", Font.BOLD, baseFontSize);
                            g2d.setFont(font);
                            fm = g2d.getFontMetrics();
                            fontReduced = true;

                            // Attempt splitting when font gets too small
                            if (baseFontSize < originalImage.getHeight() / 20) {
                                break;
                            }
                        }

                        // Determine whether we need to split the text
                        String[] lines = { chapterName };
                        if (fontReduced && chapterName.contains(" ")) {
                            int bestSplit = -1;
                            int midPoint = chapterName.length() / 2;

                            // Try to find a good split point, prioritizing after `:` or a space near the middle
                            for (int i = midPoint; i < chapterName.length(); i++) {
                                if (chapterName.charAt(i) == ':' || chapterName.charAt(i) == ' ') {
                                    bestSplit = i + 1; // Include space after the split
                                    break;
                                }
                            }

                            if (bestSplit == -1) {
                                // Fallback: Find the last space before the midpoint
                                for (int i = midPoint; i > 0; i--) {
                                    if (chapterName.charAt(i) == ' ') {
                                        bestSplit = i + 1;
                                        break;
                                    }
                                }
                            }

                            if (bestSplit > 0) {
                                lines = new String[] {
                                        chapterName.substring(0, bestSplit).trim(),
                                        chapterName.substring(bestSplit).trim()
                                };
                            }
                        }

                        // Recalculate font metrics after adjustments
                        fm = g2d.getFontMetrics(font);

                        // Calculate y-position to center the text within the bar
                        int textY = barY + (barHeight - fm.getHeight() * lines.length) / 2 + fm.getAscent();

                        // Draw the chapter name in white, line by line
                        g2d.setColor(Color.WHITE);
                        for (String line : lines) {
                            int textWidth = fm.stringWidth(line);
                            int textX = (originalImage.getWidth() - textWidth) / 2;
                            g2d.drawString(line, textX, textY);
                            textY += fm.getHeight(); // Move to next line
                        }

                        g2d.dispose();

                        // Write the modified image back
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(originalImage, extension, baos);
                        baos.writeTo(zos);
                    } else {
                        zipFile.getInputStream(entry).transferTo(zos);
                    }
                    zos.closeEntry();
                }
            }

            // Replace original file and cleanup
            Files.move(tempEPub.toPath(), epubPath, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(tempMetadata.toPath());
        } catch (Exception e) {
            EPubConverter.log.error("Failed to add EPUB metadata", e);
            throw new TrackerException("Failed to add EPUB metadata", e);
        } finally {
            try {
                if (tempMetadata != null) {
                    Files.deleteIfExists(tempMetadata.toPath());
                }

                if (tempEPub != null) {
                    Files.deleteIfExists(tempEPub.toPath());
                }
            } catch (IOException e) {
                EPubConverter.log.warn("Could not delete temporary file", e);
            }
        }
    }

    private List<String> splitTextIntoLines(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();

        for (String word : text.split(" ")) {
            if (fm.stringWidth(currentLine + word) > maxWidth) {
                lines.add(currentLine.toString().trim());
                currentLine = new StringBuilder();
            }
            currentLine.append(word).append(" ");
        }

        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString().trim());
        }

        return lines;
    }

    private String nodeToString(Node node) throws TransformerException {
        StringWriter sw = new StringWriter();
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.transform(new DOMSource(node), new StreamResult(sw));
        return sw.toString();
    }

    private void appendXmlString(Document doc, Element parent, String xmlString) throws Exception {
        Document fragment = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new InputSource(new StringReader(xmlString)));
        Node importedNode = doc.importNode(fragment.getDocumentElement(), true);
        parent.appendChild(importedNode);
    }

    private void addDcElement(Document doc, Element parent, String name, String content, Map<String, String> attributes) {
        Element element = doc.createElement("dc:" + name);
        if (attributes != null) {
            attributes.forEach(element::setAttribute);
        }
        element.setTextContent(content);
        parent.appendChild(element);
    }

    private void addMetaElement(Document doc, Element parent, String name, String content) {
        Element element = doc.createElement("meta");
        element.setAttribute("name", name);
        element.setAttribute("content", content);
        parent.appendChild(element);
    }

    private boolean isKccAvailable() {
        if (EPubConverter.KCC_SCRIPT_PATH.isBlank() && config.isConvertToEpub()) {
            throw new TrackerException("KCC_SCRIPT is missing from environment variables");
        }

        EPubConverter.log.debug("KCC found: {}", EPubConverter.KCC_SCRIPT_PATH);

        try {
            ProcessResult result = ProcessHelper.run(config,
                    String.format("python %s --help", EPubConverter.KCC_SCRIPT_PATH));

            boolean available = result.getExitCode() == 0;

            if (available) {
                EPubConverter.log.info("KCC is available");
            } else {
                throw new TrackerException(String.format("KCC is unavailable: %s", result.getErrorOutput()));
            }

            return available;
        } catch (IOException e) {
            EPubConverter.log.warn("Error checking KCC availability", e);
            return false;
        }
    }

}