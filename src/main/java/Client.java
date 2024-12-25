import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

import java.io.*;
import java.util.Objects;
import java.util.Scanner;
import java.net.Socket;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.GZIPOutputStream;

/* Chris Orr #6755383 / David Boere #6776348
 * COSC 3P95 Assignment 2
 * November 19th 2023
 */

public class Client {

    private static final String SERVER_IP = "localhost";
    private static final int SERVER_PORT = 25565;
    private static final String CLIENT_FOLDER_PATH = "client_files/";
    private static Tracer tracer = GlobalOpenTelemetry.getTracer("client-tracer");
    public static boolean consent = false;

   /* Method: main
    * Take input from user and begin file transfer
    * @params: None
    * @return: None
    */
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        while (!consent) {
            System.out.println("type 'y' to start sending files");
            if (Objects.equals(scanner.nextLine(), "y")) {
                consent = true;
            }
        }
        sendFiles();
    }

   /* Method: sendFiles
    * Send files from client to server
    * @params: None
    * @return: None
    */
    private static void sendFiles() {
        File folder = new File(CLIENT_FOLDER_PATH);
        for (File file : folder.listFiles()) {
            System.out.println("sending file " + file);
            try ( Socket socket = new Socket(SERVER_IP, SERVER_PORT);
                DataInputStream dataInputStream = new DataInputStream(socket.getInputStream()); // Create new inputStreams
                DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream())
                ) {

                //sending file metadata
                dataOutputStream.writeUTF(file.getName());
                dataOutputStream.writeLong(file.length());

                //sending file
                CRC32 crc32 = new CRC32();
                try (   FileInputStream fileInputStream = new FileInputStream(file);
                        GZIPOutputStream gzipOutputStream = new GZIPOutputStream(dataOutputStream);
                        CheckedOutputStream checkedOutputStream = new CheckedOutputStream(gzipOutputStream, crc32);
                     ) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                        checkedOutputStream.write(buffer, 0, bytesRead);
                    }
                }

                //receive checksum
                long serverChecksum = dataInputStream.readLong();

                //verify file integrity
                if (crc32.getValue() == serverChecksum) {
                    System.out.println("File sent successfully to server!");
                } else {
                    System.out.println("File integrity was compromised during transmission.");
                }

            } catch (IOException e) {
                // empty
            }
        }
    }
}