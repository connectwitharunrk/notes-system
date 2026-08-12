package com.arunrk.notes.api.v1.user

import com.arunrk.notes.api.v1.auth.UserDto
import com.arunrk.notes.domain.usecase.user.GetCurrentUserUseCase
import com.arunrk.notes.domain.usecase.user.UpdateProfileCommand
import com.arunrk.notes.domain.usecase.user.UpdateProfileUseCase
import com.arunrk.notes.infrastructure.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class UpdateProfileRequest(
    @field:NotBlank(message = "must not be blank")
    @field:Size(max = 100, message = "must be at most 100 characters")
    val name: String,
)

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
class UserController(
    private val getCurrentUser: GetCurrentUserUseCase,
    private val updateProfile: UpdateProfileUseCase,
) {

    @GetMapping("/me")
    @Operation(summary = "Get the authenticated user's profile")
    fun me(): UserDto = UserDto.from(getCurrentUser.execute(CurrentUser.id()))

    @PatchMapping("/me")
    @Operation(
        summary = "Update the authenticated user's profile",
        description = "Only the display name is mutable. Email is the login identity " +
            "and the password-reset destination, so changing it needs a verification " +
            "round trip that does not exist yet.",
    )
    fun updateMe(@Valid @RequestBody request: UpdateProfileRequest): UserDto =
        UserDto.from(
            updateProfile.execute(
                UpdateProfileCommand(userId = CurrentUser.id(), name = request.name)
            )
        )
}
