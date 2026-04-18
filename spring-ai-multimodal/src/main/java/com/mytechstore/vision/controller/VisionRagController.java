package com.mytechstore.vision.controller;

import java.util.Map;
import java.io.IOException;

import com.mytechstore.vision.dto.VisionRagRequest;
import com.mytechstore.vision.dto.VisionRagResponse;
import com.mytechstore.vision.service.OpenAiVisionExtractor;
import com.mytechstore.vision.service.VisionPipelineService;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import jakarta.validation.Valid;

@Validated
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api")
public class VisionRagController {

    private record ImageContextBundle(String context, OpenAiVisionExtractor.ConsistencyResult consistency) {
    }

    private final VisionPipelineService pipelineService;
    private final OpenAiVisionExtractor visionExtractor;

    public VisionRagController(VisionPipelineService pipelineService, OpenAiVisionExtractor visionExtractor) {
        this.pipelineService = pipelineService;
        this.visionExtractor = visionExtractor;
    }

    @PostMapping("/index/rebuild")
    public ResponseEntity<Map<String, String>> rebuildIndex() {
        return ResponseEntity.ok(Map.of("status", pipelineService.rebuildIndex()));
    }

    @PostMapping("/query/ask")
    public ResponseEntity<VisionRagResponse> ask(@Valid @RequestBody VisionRagRequest request) {
        return ResponseEntity.ok(pipelineService.ask(request.question(), request.imageDescription()));
    }

    @PostMapping(value = "/query/ask-with-image", consumes = {"multipart/form-data"})
    public ResponseEntity<VisionRagResponse> askWithImage(
            @RequestParam("question") String question,
            @RequestParam(value = "imageDescription", required = false, defaultValue = "") String imageDescription,
            @RequestParam(value = "image", required = false) MultipartFile image) {

        String cleanedQuestion = question == null ? "" : question.trim();
        if (cleanedQuestion.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }

        ImageContextBundle bundle = buildImageContext(imageDescription, image);
        VisionRagResponse baseResponse = pipelineService.ask(cleanedQuestion, bundle.context());
        OpenAiVisionExtractor.ConsistencyResult consistency = bundle.consistency();

        return ResponseEntity.ok(new VisionRagResponse(
                baseResponse.answer(),
                baseResponse.chunksUsed(),
                baseResponse.strategy(),
                consistency.label(),
                consistency.score(),
                consistency.reasons()));
    }

    private ImageContextBundle buildImageContext(String imageDescription, MultipartFile image) {
        StringBuilder context = new StringBuilder();
        String visualSignals = "";

        if (image != null && !image.isEmpty()) {
            String contentType = image.getContentType() == null ? "unknown" : image.getContentType();
            if (!contentType.startsWith("image/")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "uploaded file must be an image");
            }

            context.append("Uploaded image: name=")
                    .append(image.getOriginalFilename() == null ? "unnamed" : image.getOriginalFilename())
                    .append(", type=")
                    .append(contentType)
                    .append(", size=")
                    .append(image.getSize())
                    .append(" bytes");

            if (image.getSize() > 8 * 1024 * 1024) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "uploaded image must be 8MB or smaller");
            }

            try {
                visualSignals = visionExtractor.extractImageSignals(image.getBytes(), contentType);
                if (!visualSignals.isBlank()) {
                    context.append("\nVision extraction: ").append(visualSignals);
                }
            } catch (IOException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to read uploaded image", ex);
            }
        }

        String cleanedDescription = imageDescription == null ? "" : imageDescription.trim();
        if (!cleanedDescription.isBlank()) {
            if (!context.isEmpty()) {
                context.append("\n");
            }
            context.append("User image notes: ").append(cleanedDescription);
        }

        OpenAiVisionExtractor.ConsistencyResult consistency =
                visionExtractor.evaluateConsistency(cleanedDescription, visualSignals);
        return new ImageContextBundle(context.toString(), consistency);
    }
}