package town.kibty.hyproxy.auth.model.oauth.deviceauth;

import com.google.gson.annotations.SerializedName;

public record DeviceAuthResponse(
        @SerializedName("device_code")
        String deviceCode,
        @SerializedName("user_code")
        String userCode,
        @SerializedName("verification_uri")
        String verificationUri,
        @SerializedName("verification_uri_complete")
        String verificationUriComplete,
        @SerializedName("expires_in")
        int expiresIn,
        @SerializedName("interval")
        int interval
) {
}
