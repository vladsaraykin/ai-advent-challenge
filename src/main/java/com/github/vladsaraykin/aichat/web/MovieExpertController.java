package com.github.vladsaraykin.aichat.web;

import com.github.vladsaraykin.aichat.movie.MovieComparisonForm;
import com.github.vladsaraykin.aichat.movie.MovieChatMode;
import com.github.vladsaraykin.aichat.movie.MovieChatSession;
import com.github.vladsaraykin.aichat.movie.MovieExpertService;
import com.github.vladsaraykin.aichat.movie.MovieResponseFormat;
import com.github.vladsaraykin.aichat.history.ChatMessage;
import com.openai.errors.OpenAIServiceException;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;

@Controller
@SessionAttributes("movieChats")
public class MovieExpertController {

    private final MovieExpertService movieExpertService;

    public MovieExpertController(MovieExpertService movieExpertService) {
        this.movieExpertService = movieExpertService;
    }

    @ModelAttribute("movieChats")
    public MovieChatSession movieChats() {
        return new MovieChatSession();
    }

    @GetMapping("/movies")
    public String page(Model model) {
        if (!model.containsAttribute("movieForm")) {
            model.addAttribute("movieForm", new MovieComparisonForm());
        }
        model.addAttribute("formats", MovieResponseFormat.values());
        model.addAttribute("modes", MovieChatMode.values());
        return "movies";
    }

    @PostMapping("/movies/chat")
    public String chat(@Valid @ModelAttribute("movieForm") MovieComparisonForm form,
                          BindingResult bindingResult,
                          @ModelAttribute("movieChats") MovieChatSession chats,
                          Model model) {
        model.addAttribute("formats", MovieResponseFormat.values());
        model.addAttribute("modes", MovieChatMode.values());
        if (bindingResult.hasErrors()) {
            return "movies";
        }
        try {
            String answer = movieExpertService.send(form, chats.messages(form.getMode()));
            chats.add(form.getMode(), new ChatMessage(ChatMessage.Role.USER, form.getUserPrompt(), Instant.now()));
            chats.add(form.getMode(), new ChatMessage(ChatMessage.Role.ASSISTANT, answer, Instant.now()));
            form.setUserPrompt(null);
        } catch (RuntimeException exception) {
            model.addAttribute("error", userFacingMessage(exception));
        }
        return "movies";
    }

    @PostMapping("/movies/clear")
    public String clear(@RequestParam MovieChatMode mode,
                        @ModelAttribute("movieChats") MovieChatSession chats,
                        Model model) {
        chats.clear(mode);
        MovieComparisonForm form = new MovieComparisonForm();
        form.setMode(mode);
        model.addAttribute("movieForm", form);
        model.addAttribute("formats", MovieResponseFormat.values());
        model.addAttribute("modes", MovieChatMode.values());
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
