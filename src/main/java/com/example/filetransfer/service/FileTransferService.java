package com.example.filetransfer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * TCP storage-node service.
 *
 * Protocol (all lengths are 4-byte big-endian ints, command codes are 4 ASCII bytes):
 *   Request : "FREQ" + fileNameLen + fileName + pathLen + path
 *   Found   : "FACK" + fileSize(8 bytes) + fileBytes + md5(16 bytes)
 *   Missing : "FNOT"
 *   Delete  : "FDEL"   (sent by client after a successful transfer)
 *   Deleted : "DELOK" | "DELER"
 *
 * File lookup strategy (see findFile): candidate paths are tried across a
 * configurable list of storage roots, cheapest/most-specific match first.
 * The exact directory-layout rules are environment-specific and intentionally
 * left as a single extension point below.
 */
@Slf4j
@Service
public class FileTransferService {

    @Value("${fileserver.port:9999}")
    private int serverPort;

    @Value("${fileserver.storage.path}")
    private String storagePathRaw;

    @Value("${fileserver.thread.pool.size:10}")
    private int threadPoolSize;

    @Value("${fileserver.delete.enabled:true}")
    private boolean deleteEnabled;

    private List<String> storagePaths;

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = false;

    @PostConstruct
    public void startServer() {
        storagePaths = Arrays.stream(storagePathRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());

        executorService = Executors.newFixedThreadPool(threadPoolSize);
        running = true;

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(serverPort);
                log.info("File transfer server listening on port {} ({} storage root(s))",
                        serverPort, storagePaths.size());

                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        executorService.submit(() -> handleClient(clientSocket));
                    } catch (IOException e) {
                        if (running) {
                            log.error("Failed to accept client connection", e);
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Failed to start server socket", e);
            }
        }, "FileTransferServer").start();
    }

    @PreDestroy
    public void stopServer() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.warn("Error closing server socket", e);
            }
        }
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()))) {

            clientSocket.setSoTimeout(60000);
            clientSocket.setTcpNoDelay(true);

            byte[] commandBytes = new byte[4];
            dis.readFully(commandBytes);
            String command = new String(commandBytes, StandardCharsets.UTF_8);

            if ("FREQ".equals(command)) {
                handleFileRequest(dis, dos);
            } else {
                log.warn("Unknown command: {}", command);
            }
        } catch (IOException e) {
            log.error("Error handling client", e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleFileRequest(DataInputStream dis, DataOutputStream dos) throws IOException {
        String fileName = readLengthPrefixedString(dis);
        String filePath = readLengthPrefixedString(dis);

        File targetFile = findFile(fileName, filePath);

        if (targetFile == null) {
            dos.write("FNOT".getBytes(StandardCharsets.UTF_8));
            dos.flush();
            return;
        }

        long fileSize = targetFile.length();
        dos.write("FACK".getBytes(StandardCharsets.UTF_8));
        dos.writeLong(fileSize);
        dos.flush();

        try (FileInputStream fis = new FileInputStream(targetFile)) {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
                md5.update(buffer, 0, bytesRead);
            }
            dos.flush();
            dos.write(md5.digest());
            dos.flush();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("MD5 not available", e);
        }

        byte[] deleteCmd = new byte[4];
        dis.readFully(deleteCmd);
        if ("FDEL".equals(new String(deleteCmd, StandardCharsets.UTF_8))) {
            if (deleteEnabled && targetFile.delete()) {
                dos.write("DELOK".getBytes(StandardCharsets.UTF_8));
            } else {
                dos.write(deleteEnabled ? "DELER".getBytes(StandardCharsets.UTF_8)
                        : "DELOK".getBytes(StandardCharsets.UTF_8));
            }
            dos.flush();
        }
    }

    private String readLengthPrefixedString(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Resolve fileName + a client-supplied relative/absolute path hint to a
     * file under one of the configured storage roots.
     *
     * ponytail: directory-layout heuristics (date-bucketing, URL rewriting,
     * etc.) are environment-specific and deliberately not included here —
     * plug your own resolution rules into this method.
     */
    private File findFile(String fileName, String filePathHint) {
        for (String base : storagePaths) {
            File candidate = new File(new File(base, safeParent(filePathHint)), fileName);
            if (candidate.exists() && candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private String safeParent(String pathHint) {
        if (pathHint == null) return "";
        // Strip any drive letter / leading separators so lookups stay
        // confined to the configured storage roots.
        String normalized = pathHint.replace("\\", "/");
        if (normalized.matches("^[A-Za-z]:/.*")) {
            normalized = normalized.substring(3);
        }
        return normalized.replace("..", "");
    }
}
