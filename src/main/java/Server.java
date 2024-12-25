import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Scope;

import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.GZIPInputStream;

/* Chris Orr #6755383 / David Boere #6776348
 * COSC 3P95 Assignment 2
 * November 19th 2023
 */


class RateLimiter {

    private Map<String, Long> requestTimestamps;
    private int maxRequests;
    private long timeWindow;
    public static boolean deliberateBug = true; // Enable for Question 2

    public RateLimiter(int maxRequests, long timeWindow) {
        this.requestTimestamps = new HashMap<>();
        this.maxRequests = maxRequests;
        this.timeWindow = timeWindow;
    }

    /* Method: allowRequest
    * Ensures files are not being sent faster than the limit
    * @params: None
    * @return: boolean
    */
    public synchronized boolean allowRequest(Socket clientSocket) {
        String clientId = getClientIdentifier(clientSocket);

        long currentTime = System.currentTimeMillis();
        long windowStartTime = currentTime - timeWindow;

        // Remove old entries from the timestamps map
        requestTimestamps.entrySet().removeIf(entry -> entry.getValue() < windowStartTime);

        // Check if the number of requests within the time window exceeds the limit
        if (requestTimestamps.getOrDefault(clientId, 0L) < maxRequests) {
            // Allow the request and update the timestamp
            requestTimestamps.put(clientId, currentTime);

            return !deliberateBug; // Always reject the request for Question 2

        } else {
            return false; // Reject the request
        }
    }

    private String getClientIdentifier(Socket clientSocket) {
        return clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();        // Use client socket information as the identifier
    }
}

public class Server {

    private static final int PORT = 25565;
    private static final String SAVE_DIRECTORY = "server_files/";
    private static final String SAMPLE_DIRECTORY = "server_sampling/";
    private static double samplingRate;
    private static Tracer tracer;

   /* Method: main
    * Initializes Server on Port 25565
    * @params: String[] args
    * @return: None
    */
    public static void main(String[] args) {
        //initializing OpenTelemetry
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder().buildAndRegisterGlobal(); //can also add .setTracerProvider() and .addSpanProcessor() here if needed
        
        tracer = GlobalOpenTelemetry.getTracer("server-tracer");
        
        //determining the sampling rate to use
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter the sampling rate you would like to use, as a decimal. 1 is equivalent to Always-On:");
        samplingRate = scanner.nextDouble();
        System.out.println((samplingRate * 100) + "% sampling confirmed");

        RateLimiter rateLimiter = new RateLimiter(5, 10000); //5 requests per 10 seconds
        try ( ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server listening on port " + PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                //rate limiting check
                if (rateLimiter.allowRequest(clientSocket)) {
                    System.out.println("File request from " + getClientIdentifier(clientSocket) + " was allowed.");
                    new Thread(new ClientHandler(clientSocket)).start();
                } else {
                    System.out.println("File request from " + getClientIdentifier(clientSocket) + " was rejected due to rate limiting.");
                }

            }
        } catch (IOException e) {
            // empty
        }
    }
    
    private static class ClientHandler implements Runnable {

        private final Socket clientSocket;

        private static boolean isSampling = false;

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            Span fileSpan = tracer.spanBuilder("FileSend").startSpan();
            try (
                DataInputStream dataInputStream = new DataInputStream(clientSocket.getInputStream());  DataOutputStream dataOutputStream = new DataOutputStream(clientSocket.getOutputStream());) {
                //getting file name and size
                String fileName = dataInputStream.readUTF();
                long fileSize = dataInputStream.readLong();

                //sampling based on given probability
                if (Math.random() < samplingRate) {
                    isSampling = true;
                    System.out.println("we're going to sample this one");
                } else {
                    System.out.println("not sampling this one");
                }

                CRC32 crc32 = new CRC32(); //initializing checksum
                try (
                    GZIPInputStream gzipInputStream = new GZIPInputStream(dataInputStream);  CheckedInputStream checkedInputStream = new CheckedInputStream(gzipInputStream, crc32);  FileOutputStream fileOutputStream = new FileOutputStream(SAVE_DIRECTORY + "server_received_" + fileName);  FileOutputStream fileSampleStream = new FileOutputStream(SAMPLE_DIRECTORY + "server_received_" + fileName);) {

                    //receiving the file
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = checkedInputStream.read(buffer)) != -1) {
                        // Sampling
                        if (isSampling) {
                            fileSampleStream.write(buffer, 0, bytesRead);
                        }
                        fileOutputStream.write(buffer, 0, bytesRead);
                    }
                }

                //send calculated checksum back to client
                dataOutputStream.writeLong(crc32.getValue());
                System.out.println("File received successfully from client: " + fileName);

            } catch (IOException e) {
                fileSpan.setStatus(StatusCode.ERROR, "File send failed");
            } finally {
                fileSpan.end();
            }
        }
    }

    // Get Client ID for Rate Limiting
    private static String getClientIdentifier(Socket clientSocket) {
        return clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();
    }
}