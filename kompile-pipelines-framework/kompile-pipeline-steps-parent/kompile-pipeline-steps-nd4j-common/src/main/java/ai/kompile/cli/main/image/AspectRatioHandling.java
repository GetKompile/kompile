/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package ai.kompile.cli.main.image;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How We should handle the situation where the input image and output image/NDArray have different aspect ratios?<br>
 * Use in {@link ai.konduit.serving.data.image.convert.ImageToNDArrayConfig} and {@link ai.konduit.serving.data.image.step.resize.ImageResizeStep}
 * See {@link ai.konduit.serving.data.image.convert.ImageToNDArrayConfig} for more details<br>
 * <ul>
 *     <li>CENTER_CROP: Crop the larger dimension down to the correct aspect ratio (and then resize if necessary).</li>
 *     <li>PAD: Zero pad the smaller dimension to make the aspect ratio match the output (and then resize if necessary)</li>
 *     <li>STRETCH: Simply resize the image to the required aspect ratio, distorting the image if necessary</li>
 * </ul>
 */
@Schema(description = "An enum specifying how to handle the situation where the input image and output. NDArray have different aspect ratios. <br><br>" +
        "CENTER_CROP -> Crop the larger dimension down to the correct aspect ratio (and then resize if necessary), <br>" +
        "PAD -> Zero pad the smaller dimension to make the aspect ratio match the output (and then resize if necessary), <br>" +
        "STRETCH -> Simply resize the image to the required aspect ratio, distorting the image if necessary")
public enum AspectRatioHandling {
    CENTER_CROP, PAD, STRETCH
}
