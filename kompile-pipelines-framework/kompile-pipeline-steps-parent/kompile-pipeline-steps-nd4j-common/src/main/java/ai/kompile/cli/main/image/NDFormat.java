package ai.kompile.cli.main.image;


/**
 * The format to be used when converting an Image to an NDArray<br>
 * CHANNELS_FIRST (output shape: [1, c, h, w] or [c, h, w]) or CHANNELS_LAST (output shape: [1, h, w, c] or [h, w, c])<br>
 * See {@link ai.konduit.serving.data.image.convert.ImageToNDArrayConfig}
 */

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The format to be used when converting an Image to an NDArray. " +
        "CHANNELS_FIRST -> (output shape: [1, c, h, w] or [c, h, w]), " +
        "CHANNELS_LAST -> (output shape: [1, h, w, c] or [h, w, c]).")
public enum NDFormat {
    CHANNELS_FIRST,
    CHANNELS_LAST
}
