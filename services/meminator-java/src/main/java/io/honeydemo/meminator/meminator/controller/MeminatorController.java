package io.honeydemo.meminator.meminator.controller;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.opentelemetry.instrumentation.annotations.WithSpan;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.common.Attributes;

@RestController
public class MeminatorController {

    private static final int IMAGE_MAX_WIDTH_PX = 1000;
    private static final int IMAGE_MAX_HEIGHT_PX = 1000;

    Logger logger = LogManager.getLogger("MeminatorController");
    Tracer tracer = GlobalOpenTelemetry.getTracer("meminator-tracer");

    @SuppressWarnings("deprecation")
    @PostMapping("/applyPhraseToPicture")
    public ResponseEntity<byte[]> meminate(@RequestBody ImageRequest request) {
        File inputFile = null;
        File outputFile = null;
        Span span=Span.current();

        //Span span = tracer.spanBuilder("apply phrase").setNoParent().startSpan();

        try {
            String phrase = request.getPhrase();
            URL imageUrl = new URL(request.getImageUrl());
            
            String filename = new File(imageUrl.getPath()).getName();
            String fileExtension = getFileExtension(filename);

            span.setAttribute("app.file_extension", fileExtension);

            // download the image using URL
            BufferedImage originalImage = ImageIO.read(imageUrl);
            inputFile = new File("/tmp/" + filename);
            ImageIO.write(originalImage, fileExtension, inputFile);

            // generate output file path
            String outputFilePath = getOutputFilePath(fileExtension);
            outputFile = new File(outputFilePath);

            // run the convert command
            span.addEvent("Running convert command", Attributes.builder()
            .put("app.imageFile", "filename")
            .put("app.phrase", phrase)
            .put("app.outputFile", outputFilePath)
            .build());

            //runConvertCommand(inputFile, phrase, outputFilePath);

            runConvertCommand(new File("this does not exist"), phrase, outputFilePath);

            // read the output file back into the byte array
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BufferedImage outputImage = ImageIO.read(new File(outputFilePath));
            ImageIO.write(outputImage, fileExtension, baos);
            byte[] imageBytes = baos.toByteArray();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(getMediaType(fileExtension));
            headers.setContentLength(imageBytes.length);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(imageBytes);

        } catch (Exception e) {
            //logger.error(e.getClass() + ": " +  e.getMessage() + ": " + e.getCause(), e);

            span.recordException(e);
            span.setStatus (StatusCode.ERROR, e.getMessage());

            return ResponseEntity.status(500).build();
        } finally {
            if(inputFile != null) try { inputFile.delete(); } catch (Exception ide) { ide.printStackTrace(); }
            if(outputFile != null) try { outputFile.delete(); } catch (Exception ode) { ode.printStackTrace(); }
            span.end();
        }
    }

    private String getOutputFilePath(String extension) {
        return "/tmp/" + UUID.randomUUID().toString() + "." + extension;
    }

    private MediaType getMediaType(String fileExtension) {
        switch (fileExtension.toLowerCase()) {
            case "jpg":
            case "jpeg":
                return MediaType.IMAGE_JPEG;
            case "png":
                return MediaType.IMAGE_PNG;
            case "gif":
                return MediaType.IMAGE_GIF;
            default:
                return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private String getFileExtension(String fileName) {
        int lastIndexOfDot = fileName.lastIndexOf('.');
        return (lastIndexOfDot == -1) ? "" : fileName.substring(lastIndexOfDot + 1);
    }

    public static class ImageRequest {
        private String phrase;
        private String imageUrl;

        public ImageRequest(String phrase, String imageUrl) {
            this.phrase = phrase;
            this.imageUrl = imageUrl;
        }

        public String getPhrase() {
            return this.phrase;
        }

        public void setPhrase(String phrase) {
            this.phrase = phrase;
        }

        public String getImageUrl() {
            return this.imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }
    }

    @WithSpan
    private int runConvertCommand(File inputFile, String phrase, String outputFilePath) throws InterruptedException, IOException {

          //  Span subprocessSpan = GlobalOpenTelemetry.getTracer("pictureController").spanBuilder("convert").startSpan();
        ProcessBuilder pb = new ProcessBuilder(new String[] {
            "convert", 
            inputFile.getAbsolutePath(), 
            "-resize", 
            IMAGE_MAX_WIDTH_PX + "x" + IMAGE_MAX_HEIGHT_PX,
            "-gravity", "North",
            "-pointsize", "48",
            "-fill", "white",
            "-undercolor", "#00000080",
            "-font", "Angkor-Regular",
            "-annotate", "0",
            phrase.toUpperCase(),
            outputFilePath
        });
      //  subprocessSpan.setAttribute("app.subprocess.command", String.join(" ", pb.command()));
        pb.inheritIO();
        Process process = pb.start();

        InputStream stream = process.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        StringBuilder output = new StringBuilder();
        String line = null;
        while((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }

        InputStream errStream = process.getErrorStream();
        BufferedReader errReader = new BufferedReader(new InputStreamReader(errStream));
        StringBuilder error = new StringBuilder();
        String errLine = "";
        while((errLine = errReader.readLine()) != null) {
            error.append(errLine).append("\n");
        }

        int exitCode = process.waitFor();
                    // subprocessSpan.setAttribute("app.subprocess.returncode", exitCode);
            // subprocessSpan.setAttribute("app.subprocess.stdout", output.toString());
            // subprocessSpan.setAttribute("app.subprocess.stderr", error.toString());
            // subprocessSpan.end();
        return exitCode;
    }
}


