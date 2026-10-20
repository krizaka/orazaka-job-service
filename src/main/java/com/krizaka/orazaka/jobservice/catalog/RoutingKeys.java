package com.krizaka.orazaka.jobservice.catalog;

/**
 * AMQP topic matching, the way the broker binds a worker's queue: {@code *} is one word, {@code #}
 * any number of words.
 */
public final class RoutingKeys {

  private RoutingKeys() {}

  /**
   * @param binding what a worker declared it drains ({@code job.video.*})
   * @param key a capability's routing key ({@code job.video.generate})
   * @return whether the broker would deliver a message with that key to that binding
   */
  public static boolean matches(String binding, String key) {
    return match(binding.split("\\."), 0, key.split("\\."), 0);
  }

  private static boolean match(String[] pattern, int p, String[] words, int w) {
    if (p == pattern.length) {
      return w == words.length;
    }
    if ("#".equals(pattern[p])) {
      for (int skip = w; skip <= words.length; skip++) {
        if (match(pattern, p + 1, words, skip)) {
          return true;
        }
      }
      return false;
    }
    if (w == words.length) {
      return false;
    }
    return ("*".equals(pattern[p]) || pattern[p].equals(words[w]))
        && match(pattern, p + 1, words, w + 1);
  }
}
