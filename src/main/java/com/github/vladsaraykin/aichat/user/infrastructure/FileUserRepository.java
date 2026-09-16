package com.github.vladsaraykin.aichat.user.infrastructure;

import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import com.github.vladsaraykin.aichat.user.application.UserRepository;
import com.github.vladsaraykin.aichat.user.domain.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class FileUserRepository implements UserRepository {
    private final Path directory;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public FileUserRepository(@Value("${app.users.directory:data/agent-chats/_users}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }
    private Path accounts() { return directory.resolve("accounts.json"); }
    private Path profilePath(String username) { return directory.resolve("profiles").resolve(username + ".json"); }
    @Override public synchronized Optional<UserAccount> account(String username) {
        return accountsList().stream().filter(account -> account.username().equals(username)).findFirst();
    }
    @Override public synchronized UserProfile profile(String username) {
        validateUsername(username);
        Path path = profilePath(username);
        if (!Files.exists(path)) throw new ChatFailure(ChatFailure.Kind.NOT_FOUND, "Профиль пользователя не найден");
        try { return mapper.readValue(Files.readString(path), UserProfile.class); }
        catch (Exception e) { throw storageFailure(); }
    }
    @Override public synchronized UserProfile create(UserAccount account, UserProfile profile) {
        validateUsername(account.username());
        var accounts = new ArrayList<>(accountsList());
        if (accounts.stream().anyMatch(value -> value.username().equals(account.username()))) {
            throw new ChatFailure(ChatFailure.Kind.INVALID, "Пользователь с таким логином уже существует");
        }
        accounts.add(account);
        write(accounts(), accounts);
        try { write(profilePath(account.username()), profile); }
        catch (RuntimeException e) {
            accounts.removeLast();
            write(accounts(), accounts);
            throw e;
        }
        return profile;
    }
    @Override public synchronized UserProfile saveProfile(String username, long expectedVersion, UserProfile value) {
        UserProfile current = profile(username);
        if (current.version() != expectedVersion) throw new ChatFailure(ChatFailure.Kind.BUSY,
                "Профиль уже изменился. Обновите данные и повторите действие.");
        if (!username.equals(value.username())) throw new ChatFailure(ChatFailure.Kind.INVALID, "Нельзя изменить владельца профиля");
        write(profilePath(username), value);
        return value;
    }
    private List<UserAccount> accountsList() {
        if (!Files.exists(accounts())) return List.of();
        try {
            var type = mapper.getTypeFactory().constructCollectionType(List.class, UserAccount.class);
            return mapper.readValue(Files.readString(accounts()), type);
        } catch (Exception e) { throw storageFailure(); }
    }
    private void write(Path target, Object value) {
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".user-", ".tmp");
            Files.writeString(temp, mapper.writeValueAsString(value));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { throw storageFailure(); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) { } }
    }
    public static void validateUsername(String username) {
        if (username == null || !username.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{2,39}")) {
            throw new ChatFailure(ChatFailure.Kind.INVALID, "Логин: 3–40 символов, латиница, цифры, точка, дефис или подчёркивание");
        }
    }
    private static ChatFailure storageFailure() {
        return new ChatFailure(ChatFailure.Kind.STORAGE, "Не удалось прочитать или сохранить профиль пользователя");
    }
}
