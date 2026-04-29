package io.ditto.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DittoTcpClientAutoReconnectSmokeTest {

    @Test
    void pingReconnectBehavior() throws Exception {
        runCase(false, false);
        runCase(true, true);
    }

    private static void runCase(boolean autoReconnect, boolean wantOk) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            AtomicReference<Throwable> serverError = new AtomicReference<>();

            Thread t = new Thread(() -> {
                try {
                    Socket conn1 = server.accept();
                    try (conn1) {
                        assertEquals(DittoTcpClient.Wire.REQ_PING, readRequestVariant(conn1));
                    }

                    server.setSoTimeout(500);
                    Socket conn2;
                    try {
                        conn2 = server.accept();
                    } catch (SocketTimeoutException timeout) {
                        if (autoReconnect) {
                            throw timeout;
                        }
                        return;
                    }

                    try (conn2) {
                        assertEquals(DittoTcpClient.Wire.REQ_PING, readRequestVariant(conn2));
                        writeFramedResponse(conn2, DittoTcpClient.Wire.RESP_PONG, new byte[0]);
                    }
                } catch (Throwable e) {
                    serverError.set(e);
                }
            });
            t.start();

            try (DittoTcpClient client = new DittoTcpClient("127.0.0.1", port, null, false, autoReconnect)) {
                client.connect();
                if (wantOk) {
                    assertTrue(client.ping());
                } else {
                    assertThrows(IOException.class, client::ping);
                }
            }

            t.join(2000);
            if (serverError.get() != null) {
                throw new AssertionError(serverError.get());
            }
        }
    }

    private static int readRequestVariant(Socket conn) throws Exception {
        DataInputStream in = new DataInputStream(conn.getInputStream());
        int payloadLen = in.readInt();
        byte[] payload = in.readNBytes(payloadLen);
        return ProtoTestSupport.readClientRequestVariant(payload);
    }

    private static void writeFramedResponse(Socket conn, int variantField, byte[] inner) throws Exception {
        DataOutputStream out = new DataOutputStream(conn.getOutputStream());
        out.write(ProtoTestSupport.frameClientResponse(variantField, inner));
        out.flush();
    }
}
