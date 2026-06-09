package pt.properia.api.modules.listings.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Concatenates multiple MP4 clips into a single video using ffmpeg.
 * Used to stitch together per-room Kling clips into a full property tour.
 */
@Service
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .build();

    /**
     * Downloads a remote video URL to a new temp file.
     * Caller is responsible for deleting the returned file.
     */
    public Path downloadToTemp(String url) throws Exception {
        var dest = Files.createTempFile("properia-clip-", ".mp4");
        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofFile(dest));
        if (response.statusCode() >= 300) {
            Files.deleteIfExists(dest);
            throw new RuntimeException("Falha ao baixar vídeo: HTTP " + response.statusCode());
        }
        log.debug("Downloaded video to temp: {} bytes", Files.size(dest));
        return dest;
    }

    /**
     * Overlays a "Properia.pt | Gerado por IA" watermark on the bottom-right corner.
     * Serves dual purpose: EU AI Act Art. 50 disclosure + brand protection.
     * Non-fatal: returns the input path unchanged if FFmpeg fails.
     * Caller is responsible for deleting the returned file.
     */
    public Path addWatermark(Path input) {
        try {
            var output = Files.createTempFile("properia-tour-wm-", ".mp4");
            var filter = buildWatermarkFilter();
            var cmd = List.of(
                "ffmpeg", "-y",
                "-i", input.toAbsolutePath().toString(),
                "-vf", filter,
                "-c:v", "libx264", "-crf", "22", "-preset", "fast",
                "-c:a", "copy",
                output.toAbsolutePath().toString()
            );
            log.info("Applying watermark to virtual tour");
            var process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes());
            var exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("ffmpeg watermark failed (exit {}): {} — using unwatermarked video",
                    exitCode, out.substring(0, Math.min(500, out.length())));
                Files.deleteIfExists(output);
                return input;
            }
            log.info("Watermark applied: {} bytes", Files.size(output));
            return output;
        } catch (Exception e) {
            log.warn("addWatermark failed: {} — using unwatermarked video", e.getMessage());
            return input;
        }
    }

    private String buildWatermarkFilter() {
        var fontPath = findSystemFont();
        var sb = new StringBuilder("drawtext=text='Properia.pt  |  Gerado por IA'");
        if (fontPath != null) sb.append(":fontfile=").append(fontPath);
        sb.append(":fontsize=15");
        sb.append(":fontcolor=white@0.90");
        sb.append(":x=w-tw-16");
        sb.append(":y=h-th-16");
        sb.append(":box=1");
        sb.append(":boxcolor=black@0.45");
        sb.append(":boxborderw=8");
        return sb.toString();
    }

    private String findSystemFont() {
        var candidates = List.of(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/liberation/LiberationSans-Regular.ttf"
        );
        return candidates.stream()
            .filter(p -> java.nio.file.Files.exists(java.nio.file.Path.of(p)))
            .findFirst()
            .orElse(null);
    }

    /**
     * Downloads all clips, concatenates them, overlays background music,
     * and returns the path to the final video file.
     * Caller is responsible for deleting the returned file after use.
     */
    public Path concatenate(List<String> videoUrls) throws Exception {
        var workDir = Files.createTempDirectory("properia-tour-" + UUID.randomUUID());
        try {
            var clipPaths  = downloadClips(videoUrls, workDir);
            var concatPath = workDir.resolve("tour_concat.mp4");
            runFfmpegConcat(clipPaths, concatPath);

            var musicPath  = resolveMusicPath();
            var outputPath = workDir.resolve("tour_final.mp4");
            if (musicPath != null) {
                runFfmpegAddMusic(concatPath, musicPath, outputPath);
            } else {
                Files.copy(concatPath, outputPath);
            }

            var finalPath = Files.createTempFile("properia-tour-", ".mp4");
            Files.copy(outputPath, finalPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return finalPath;
        } finally {
            deleteDirectory(workDir);
        }
    }

    private List<Path> downloadClips(List<String> urls, Path dir) throws Exception {
        var paths = new java.util.ArrayList<Path>();
        for (int i = 0; i < urls.size(); i++) {
            var dest = dir.resolve("clip_" + i + ".mp4");
            var request = HttpRequest.newBuilder()
                .uri(URI.create(urls.get(i)))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofFile(dest));
            if (response.statusCode() >= 300) {
                throw new RuntimeException("Falha ao baixar clip " + i + ": HTTP " + response.statusCode());
            }
            paths.add(dest);
            log.debug("Downloaded clip {}: {} bytes", i, Files.size(dest));
        }
        return paths;
    }

    private void runFfmpegAddMusic(Path video, Path music, Path output) throws Exception {
        // Mix background music at low volume (25%), loop if shorter than video, trim to video length
        var cmd = List.of(
            "ffmpeg", "-y",
            "-i", video.toAbsolutePath().toString(),
            "-stream_loop", "-1",
            "-i", music.toAbsolutePath().toString(),
            "-filter_complex", "[1:a]volume=0.22[music];[music]afade=t=out:st=" + getVideoDuration(video) + ":d=2[faded]",
            "-map", "0:v",
            "-map", "[faded]",
            "-c:v", "copy",
            "-c:a", "aac", "-b:a", "128k",
            "-shortest",
            output.toAbsolutePath().toString()
        );

        log.info("Adding background music to tour");
        var process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes());
        var exitCode = process.waitFor();
        if (exitCode != 0) {
            log.error("ffmpeg add-music failed (exit {}): {}", exitCode, out);
            // Non-fatal: fall back to video without music
            Files.copy(video, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
            log.info("Music added: {} bytes", Files.size(output));
        }
    }

    private double getVideoDuration(Path video) {
        try {
            var probe = new ProcessBuilder(
                "ffprobe", "-v", "quiet", "-print_format", "compact",
                "-show_entries", "format=duration", video.toAbsolutePath().toString()
            ).redirectErrorStream(true).start();
            var out = new String(probe.getInputStream().readAllBytes());
            probe.waitFor();
            // format|duration=25.066667
            var parts = out.strip().split("=");
            return parts.length > 1 ? Double.parseDouble(parts[parts.length - 1]) - 2 : 23;
        } catch (Exception e) {
            return 23; // fallback: 25s - 2s fade
        }
    }

    private Path resolveMusicPath() {
        try {
            var resource = getClass().getClassLoader().getResource("static/audio/tour-ambient.mp3");
            if (resource != null) {
                var uri = resource.toURI();
                if ("jar".equals(uri.getScheme())) {
                    // Running from JAR — extract to temp
                    var tmp = Files.createTempFile("properia-music-", ".mp3");
                    try (var is = getClass().getClassLoader().getResourceAsStream("static/audio/tour-ambient.mp3")) {
                        if (is != null) Files.copy(is, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    return tmp;
                }
                return Path.of(uri);
            }
        } catch (Exception e) {
            log.warn("Could not resolve music file: {}", e.getMessage());
        }
        return null;
    }

    private void runFfmpegConcat(List<Path> clips, Path output) throws Exception {
        // Write a concat list file for ffmpeg
        var listFile = output.getParent().resolve("concat_list.txt");
        var lines = clips.stream().map(p -> "file '" + p.toAbsolutePath() + "'").toList();
        Files.write(listFile, lines);

        // ffmpeg -f concat -safe 0 -i list.txt -c copy output.mp4
        var cmd = List.of(
            "ffmpeg", "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", listFile.toAbsolutePath().toString(),
            "-c", "copy",
            output.toAbsolutePath().toString()
        );

        log.info("Running ffmpeg concat: {} clips → {}", clips.size(), output.getFileName());
        var process = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start();

        var ffmpegOutput = new String(process.getInputStream().readAllBytes());
        var exitCode = process.waitFor();

        if (exitCode != 0) {
            log.error("ffmpeg failed (exit {}): {}", exitCode, ffmpegOutput);
            throw new RuntimeException("ffmpeg concat falhou com exit code " + exitCode);
        }
        log.info("ffmpeg concat done: {} bytes", Files.size(output));
    }

    private void deleteDirectory(Path dir) {
        try {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        } catch (IOException ignored) {}
    }
}
