package com.github.vladsaraykin.aichat.user.application;

import com.github.vladsaraykin.aichat.user.domain.*;
import java.util.Optional;

public interface UserRepository {
    Optional<UserAccount> account(String username);
    UserProfile profile(String username);
    UserProfile create(UserAccount account, UserProfile profile);
    UserProfile saveProfile(String username, long expectedVersion, UserProfile profile);
}
