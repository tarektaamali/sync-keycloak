package com.orga.usersync.model;

import java.util.List;

public record UserDto(String username, String email, String firstName,
                      String lastName, boolean enabled, List<String> roles) {}
