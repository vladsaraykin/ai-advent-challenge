package com.github.vladsaraykin.aichat.web;

import com.github.vladsaraykin.aichat.movie.MovieComparisonForm;
import com.github.vladsaraykin.aichat.movie.MovieExpertService;
import com.github.vladsaraykin.aichat.movie.MovieResponseFormat;
import com.openai.errors.OpenAIServiceException;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class MovieExpertController {

    private final MovieExpertService movieExpertService;

    public MovieExpertController(MovieExpertService movieExpertService) {
        this.movieExpertService = movieExpertService;
    }

    @GetMapping("/movies")
    public String page(Model model) {
        if (!model.containsAttribute("movieForm")) {
            model.addAttribute("movieForm", new MovieComparisonForm());
        }
        model.addAttribute("formats", MovieResponseFormat.values());
        return "movies";
    }

    @PostMapping("/movies/compare")
    public String compare(@Valid @ModelAttribute("movieForm") MovieComparisonForm form,
                          BindingResult bindingResult,
                          Model model) {
        model.addAttribute("formats", MovieResponseFormat.values());
        if (bindingResult.hasErrors()) {
            return "movies";
        }
        try {
            model.addAttribute("comparison", movieExpertService.compare(form));
        } catch (RuntimeException exception) {
            model.addAttribute("error", userFacingMessage(exception));
        }
        return "movies";
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
        return exception.getMessage() == null ? "Не удалось получить ответы модели." : exception.getMessage();
    }
}
