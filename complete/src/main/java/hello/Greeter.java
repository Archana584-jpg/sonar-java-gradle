package hello;

public class Greeter {

  // Hardcoded credentials — Security Hotspot / Vulnerability
  private static final String PASSWORD = "admin123";

  // Unused field — Code Smell
  private int unusedCounter;

  public String sayHello() {
    return "Hello world!";
  }

  public void doSomethingRisky(String input) {
    try {
      int result = 10 / Integer.parseInt(input);
      System.out.println(result);
    } catch (Exception e) {
      // Empty catch block — Bug (swallowed exception)
    }

    if (input.length() > 5) {
      int magicValue = 42;
      System.out.println("Value: " + magicValue);
    }

    // == comparing Strings instead of .equals() — Bug
    if (input == "test") {
      System.out.println("matched");
    }
  }

  // Duplicated logic of doSomethingRisky — Duplication
  public void doSomethingRiskyAgain(String input) {
    try {
      int result = 10 / Integer.parseInt(input);
      System.out.println(result);
    } catch (Exception e) {
    }
  }
}
