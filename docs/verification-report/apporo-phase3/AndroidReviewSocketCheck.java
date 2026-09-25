import java.net.*;
public class AndroidReviewSocketCheck {
 public static void main(String[] args) throws Exception {
  try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
   try (Socket client = new Socket("127.0.0.1", server.getLocalPort()); Socket peer = server.accept()) {
    System.out.println("PASS owned JVM loopback TCP");
   }
  }
 }
}
