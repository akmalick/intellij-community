// "Replace with anyMatch()" "true-preview"

import java.util.List;

public class Main {
  boolean find(List<String> data) {
      return data.stream().map(String::trim).anyMatch(trimmed -> trimmed.startsWith("xyz"));
  }
}
