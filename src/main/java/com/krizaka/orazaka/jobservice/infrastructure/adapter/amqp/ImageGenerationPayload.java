package com.krizaka.orazaka.jobservice.infrastructure.adapter.amqp;

import com.krizaka.orazaka.core.domain.model.image.ImageDirection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an image job asks for beyond its prompt, read once at the AMQP boundary (ERR-127).
 *
 * <p>A Studio step resolves every input through its template grammar, so a seed arrives as the
 * string {@code "4242"}, an optional input that was left empty as {@code ""}, and the size as the
 * {@code "1024x1280"} the blueprint's enum declares. This record turns that into validated values
 * before anything else sees the payload; a value that is present and malformed is refused rather
 * than silently replaced by a default — a seed the user typed is a promise of reproducibility.
 *
 * @param width requested width in pixels, {@code null} for the adapter's default
 * @param height requested height in pixels, {@code null} for the adapter's default
 * @param direction negative prompt, seed and step count
 */
record ImageGenerationPayload(Integer width, Integer height, ImageDirection direction) {

  /** {@code WIDTHxHEIGHT}, the OpenAI images API's own size grammar. */
  private static final Pattern SIZE =
      Pattern.compile("^\\s*(\\d{2,4})\\s*[xX×]\\s*(\\d{2,4})\\s*$");

  /** Diffusion latents are 1/8 of the image and the DiT models patch them by 2. */
  private static final int GRID = 16;

  private static final int MIN_SIDE = 256;
  private static final int MAX_SIDE = 2048;

  /** Compact canonical constructor: both sides or neither, each on the latent grid (ERR-106). */
  ImageGenerationPayload {
    if ((width == null) != (height == null)) {
      throw new IllegalArgumentException("size must give both a width and a height");
    }
    if (width != null) {
      requireSide(width, "width");
      requireSide(height, "height");
    }
    direction = direction == null ? ImageDirection.none() : direction;
  }

  private static void requireSide(int side, String name) {
    if (side < MIN_SIDE || side > MAX_SIDE || side % GRID != 0) {
      throw new IllegalArgumentException(
          name
              + " must be a multiple of "
              + GRID
              + " between "
              + MIN_SIDE
              + " and "
              + MAX_SIDE
              + ", was "
              + side);
    }
  }

  /**
   * @param payload the job command's payload
   * @return the validated request, with absent and blank values left to the model's recipe
   * @throws IllegalArgumentException when a value is present and malformed
   */
  static ImageGenerationPayload from(Map<String, Object> payload) {
    Integer width = null;
    Integer height = null;
    String size = text(payload.get("size"));
    if (size != null) {
      Matcher matcher = SIZE.matcher(size);
      if (!matcher.matches()) {
        throw new IllegalArgumentException("size must read WIDTHxHEIGHT, was '" + size + "'");
      }
      width = Integer.parseInt(matcher.group(1));
      height = Integer.parseInt(matcher.group(2));
    }
    ImageDirection direction =
        new ImageDirection(
            text(payload.get("negativePrompt")),
            whole(payload.get("seed"), "seed"),
            toInt(whole(payload.get("steps"), "steps")));
    return new ImageGenerationPayload(width, height, direction);
  }

  /**
   * @param seed the seed to start from when the request named none
   * @return this request with that seed — so the job reports the seed it actually ran
   */
  ImageGenerationPayload withSeedIfAbsent(long seed) {
    if (direction.seed() != null) {
      return this;
    }
    return new ImageGenerationPayload(
        width, height, new ImageDirection(direction.negativePrompt(), seed, direction.steps()));
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String s = value.toString().strip();
    return s.isEmpty() ? null : s;
  }

  private static Long whole(Object value, String name) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    String s = text(value);
    if (s == null) {
      return null;
    }
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be a whole number, was '" + s + "'", e);
    }
  }

  private static Integer toInt(Long value) {
    return value == null ? null : Math.toIntExact(value);
  }
}
