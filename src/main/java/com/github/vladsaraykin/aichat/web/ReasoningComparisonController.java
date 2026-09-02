package com.github.vladsaraykin.aichat.web;

import com.github.vladsaraykin.aichat.reasoning.ReasoningComparison;
import com.github.vladsaraykin.aichat.reasoning.ReasoningComparisonForm;
import com.github.vladsaraykin.aichat.reasoning.ReasoningComparisonService;
import com.github.vladsaraykin.aichat.reasoning.ReasoningMarkdownRenderer;
import com.github.vladsaraykin.aichat.reasoning.OpenAiModelCatalog;
import com.github.vladsaraykin.aichat.reasoning.ApproachResult;
import com.github.vladsaraykin.aichat.reasoning.ReasoningApproach;
import com.openai.errors.OpenAIServiceException;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class ReasoningComparisonController {

    private static final String SESSION_RESULT = "reasoningComparison";
    private final ReasoningComparisonService service;
    private final ReasoningMarkdownRenderer markdownRenderer;

    public ReasoningComparisonController(ReasoningComparisonService service,
                                         ReasoningMarkdownRenderer markdownRenderer) {
        this.service = service;
        this.markdownRenderer = markdownRenderer;
    }

    @GetMapping("/reasoning")
    public String page(Model model, HttpSession session) {
        if (!model.containsAttribute("reasoningForm")) {
            model.addAttribute("reasoningForm", new ReasoningComparisonForm());
        }
        addStoredComparison(model, session);
        addModels(model);
        return "reasoning";
    }

    @PostMapping("/reasoning/compare")
    public String compare(@Valid @ModelAttribute("reasoningForm") ReasoningComparisonForm form,
                          BindingResult bindingResult, Model model, HttpSession session) {
        addModels(model);
        if (bindingResult.hasErrors()) {
            addStoredComparison(model, session);
            return "reasoning";
        }
        try {
            ReasoningComparison comparison = service.compare(form);
            session.setAttribute(SESSION_RESULT, comparison);
            model.addAttribute("comparison", comparison);
        } catch (RuntimeException exception) {
            model.addAttribute("error", userFacingMessage(exception));
            addStoredComparison(model, session);
        }
        return "reasoning";
    }

    @GetMapping("/reasoning/download")
    public ResponseEntity<byte[]> download(HttpSession session) {
        ReasoningComparison comparison = (ReasoningComparison) session.getAttribute(SESSION_RESULT);
        if (comparison == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] content = comparison.markdown().getBytes(StandardCharsets.UTF_8);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("reasoning-comparison.md", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(content);
    }

    @GetMapping("/reasoning/approaches/{approach}")
    public String approach(@PathVariable String approach, Model model, HttpSession session) {
        ReasoningComparison comparison = (ReasoningComparison) session.getAttribute(SESSION_RESULT);
        if (comparison == null) {
            throw new ResponseStatusException(NOT_FOUND, "Сначала выполните сравнение подходов");
        }

        ReasoningApproach requestedApproach = approachFromSlug(approach);
        ApproachResult result = comparison.results().stream()
                .filter(candidate -> candidate.approach() == requestedApproach)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Результат подхода не найден"));

        model.addAttribute("comparison", comparison);
        model.addAttribute("result", result);
        model.addAttribute("renderedTurns", markdownRenderer.render(result.turns()));
        model.addAttribute("approachSlug", approachSlug(result.approach()));
        model.addAttribute("approachNumber", comparison.results().indexOf(result) + 1);
        return "reasoning-approach";
    }

    private static ReasoningApproach approachFromSlug(String slug) {
        return switch (slug) {
            case "direct" -> ReasoningApproach.DIRECT;
            case "step-by-step" -> ReasoningApproach.STEP_BY_STEP;
            case "generated-prompt" -> ReasoningApproach.GENERATED_PROMPT;
            case "expert-panel" -> ReasoningApproach.EXPERT_PANEL;
            default -> throw new ResponseStatusException(NOT_FOUND, "Неизвестный подход");
        };
    }

    public static String approachSlug(ReasoningApproach approach) {
        return switch (approach) {
            case DIRECT -> "direct";
            case STEP_BY_STEP -> "step-by-step";
            case GENERATED_PROMPT -> "generated-prompt";
            case EXPERT_PANEL -> "expert-panel";
        };
    }

    private static void addStoredComparison(Model model, HttpSession session) {
        Object comparison = session.getAttribute(SESSION_RESULT);
        if (comparison != null && !model.containsAttribute("comparison")) {
            model.addAttribute("comparison", comparison);
        }
    }

    private static void addModels(Model model) {
        model.addAttribute("openAiModels", OpenAiModelCatalog.CHAT_MODELS);
    }

    private static String userFacingMessage(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof OpenAIServiceException serviceException) {
                return "Запрос к модели завершился ошибкой (HTTP " + serviceException.statusCode() + "): "
                        + serviceException.body();
            }
            current = current.getCause();
        }
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Не удалось выполнить сравнение." : exception.getMessage();
    }
}
