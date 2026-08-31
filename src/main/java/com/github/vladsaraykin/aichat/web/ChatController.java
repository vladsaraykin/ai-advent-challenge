package com.github.vladsaraykin.aichat.web;

import com.openai.errors.OpenAIServiceException;
import com.github.vladsaraykin.aichat.chat.ChatForm;
import com.github.vladsaraykin.aichat.chat.ChatService;
import com.github.vladsaraykin.aichat.chat.AiProvider;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/")
    public String index(Model model) {
        var conversation = chatService.conversation();
        if (!model.containsAttribute("chatForm")) {
            ChatForm form = new ChatForm();
            form.setSystemPrompt(conversation.systemPrompt());
            model.addAttribute("chatForm", form);
        }
        model.addAttribute("conversation", conversation);
        model.addAttribute("providers", AiProvider.values());
        return "chat";
    }

    @PostMapping("/chat")
    public String chat(@Valid @ModelAttribute ChatForm chatForm,
                       BindingResult bindingResult,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("conversation", chatService.conversation());
            model.addAttribute("providers", AiProvider.values());
            return "chat";
        }

        try {
            chatService.send(chatForm);
            redirectAttributes.addFlashAttribute("notice", "Response received and saved.");
            chatForm.setUserPrompt(null);
            redirectAttributes.addFlashAttribute("chatForm", chatForm);
        } catch (RuntimeException exception) {
            model.addAttribute("conversation", chatService.conversation());
            model.addAttribute("providers", AiProvider.values());
            model.addAttribute("error", userFacingMessage(exception));
            return "chat";
        }
        return "redirect:/";
    }

    @PostMapping("/chat/clear")
    public String clear(RedirectAttributes redirectAttributes) {
        chatService.clear();
        redirectAttributes.addFlashAttribute("notice", "Chat history cleared.");
        return "redirect:/";
    }

    private static String userFacingMessage(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof OpenAIServiceException serviceException) {
                return "The LLM request failed (HTTP " + serviceException.statusCode() + "): "
                        + serviceException.body();
            }
            current = current.getCause();
        }
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "The LLM request failed. Check the server logs and API configuration."
                : "The LLM request failed: " + message;
    }
}
