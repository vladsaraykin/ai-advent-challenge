package com.github.vladsaraykin.aichat.user.api;

import com.github.vladsaraykin.aichat.user.application.UserService;
import com.github.vladsaraykin.aichat.user.domain.UserProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UserController {
    private final UserService service;
    public UserController(UserService service) { this.service = service; }
    public record RegisterRequest(@NotBlank @Size(min=3,max=40) String username,
                                  @NotBlank @Size(min=8,max=200) String password,
                                  @NotNull @Size(max=80) String displayName) { }
    public record ProfileEdit(@PositiveOrZero long version, @NotBlank @Size(max=80) String displayName,
                              @NotBlank @Size(max=500) String responseStyle,
                              @NotBlank @Size(max=500) String responseFormat,
                              @NotNull @Size(max=20) List<@NotBlank @Size(max=300) String> constraints) { }
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public UserProfile register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request.username(), request.password(), request.displayName());
    }
    @GetMapping("/auth/me") public UserProfile me(Principal principal) { return service.profile(principal.getName()); }
    @GetMapping("/profile") public UserProfile profile(Principal principal) { return service.profile(principal.getName()); }
    @PutMapping("/profile") public UserProfile update(Principal principal, @Valid @RequestBody ProfileEdit request) {
        return service.update(principal.getName(), request.version(), request.displayName(), request.responseStyle(),
                request.responseFormat(), request.constraints());
    }
}
