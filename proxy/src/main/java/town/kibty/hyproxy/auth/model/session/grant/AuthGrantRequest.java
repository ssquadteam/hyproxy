package town.kibty.hyproxy.auth.model.session.grant;

import com.google.gson.annotations.SerializedName;

public record AuthGrantRequest(
        String identityToken,
        @SerializedName("aud")
        String audience
) {
}
