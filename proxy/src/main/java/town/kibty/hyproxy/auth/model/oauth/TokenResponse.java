package town.kibty.hyproxy.auth.model.oauth;

import com.google.gson.annotations.SerializedName;
import org.jspecify.annotations.Nullable;

public record TokenResponse(
        @SerializedName("access_token")
        String accessToken,
        @SerializedName("refresh_token")
        String refreshToken,
        @SerializedName("id_token")
        String idToken,
        @SerializedName("error")
        @Nullable String error,
        @SerializedName("expires_in")
        @Nullable Integer expiresIn
) {
}
