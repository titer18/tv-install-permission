package com.hz.adbperm;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * Minimal ADB client: connects to the local adbd over TCP, authenticates with a
 * generated RSA key and runs shell commands.
 */
public class AdbClient {

    private static final int A_SYNC = 0x434e5953;
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_AUTH = 0x48545541;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private PrivateKey privateKey;
    private PublicKey publicKey;
    private int nextLocalId = 1;

    private static class Msg {
        int cmd;
        int arg0;
        int arg1;
        byte[] data;
    }

    public void connect(String host, int port, File keyDir) throws Exception {
        loadOrCreateKey(keyDir);

        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 4000);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = socket.getOutputStream();

        write(A_CNXN, 0x01000001, 256 * 1024, "host::".getBytes("UTF-8"));

        boolean sentSignature = false;
        boolean sentPublicKey = false;
        while (true) {
            Msg m = read();
            if (m.cmd == A_CNXN) {
                return;
            }
            if (m.cmd == A_AUTH) {
                if (m.arg0 == 1) {
                    if (!sentSignature) {
                        sentSignature = true;
                        write(A_AUTH, 2, 0, sign(m.data));
                    } else if (!sentPublicKey) {
                        sentPublicKey = true;
                        write(A_AUTH, 3, 0, publicKeyPayload());
                    } else {
                        throw new IOException("ADB 授权被拒绝");
                    }
                } else {
                    throw new IOException("ADB 认证失败");
                }
                continue;
            }
            if (m.cmd == A_CLSE) {
                throw new IOException("ADB 连接被关闭");
            }
        }
    }

    public String exec(String command) throws IOException {
        int localId = nextLocalId++;
        write(A_OPEN, localId, 0, ("shell:" + command + "\0").getBytes("UTF-8"));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int remoteId = -1;
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY && m.arg1 == localId) {
                remoteId = m.arg0;
            } else if (m.cmd == A_WRTE && m.arg1 == localId) {
                buffer.write(m.data);
                if (remoteId >= 0) {
                    write(A_OKAY, remoteId, localId, new byte[0]);
                }
            } else if (m.cmd == A_CLSE && m.arg1 == localId) {
                if (remoteId >= 0) {
                    write(A_CLSE, remoteId, localId, new byte[0]);
                }
                return buffer.toString("UTF-8");
            }
        }
    }

    /** Runs a shell command and fails if it produced a non zero exit marker. */
    public String execChecked(String command) throws IOException {
        String wrapped = command + " ; echo __RC=$?";
        String out = exec(wrapped);
        int idx = out.lastIndexOf("__RC=");
        if (idx >= 0) {
            String tail = out.substring(idx + 5).trim();
            String body = out.substring(0, idx);
            if (!"0".equals(tail)) {
                throw new IOException("命令执行失败 rc=" + tail + " " + body.trim());
            }
            return body;
        }
        return out;
    }

    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void loadOrCreateKey(File dir) throws Exception {
        File keyFile = new File(dir, "adbkey.pk8");
        if (!keyFile.exists()) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            FileOutputStream fos = new FileOutputStream(keyFile);
            try {
                fos.write(pair.getPrivate().getEncoded());
            } finally {
                fos.close();
            }
            privateKey = pair.getPrivate();
            publicKey = pair.getPublic();
            return;
        }
        byte[] bytes = readAll(keyFile);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
        privateKey = KeyFactory.getInstance("RSA").generatePrivate(spec);
        RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(
                ((java.security.interfaces.RSAPrivateCrtKey) privateKey).getModulus(),
                ((java.security.interfaces.RSAPrivateCrtKey) privateKey).getPublicExponent());
        publicKey = KeyFactory.getInstance("RSA").generatePublic(pubSpec);
    }

    private byte[] sign(byte[] token) throws Exception {
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(privateKey);
        signature.update(token);
        return signature.sign();
    }

    private byte[] publicKeyPayload() throws IOException {
        RSAPublicKey rsa = (RSAPublicKey) publicKey;
        BigInteger modulus = rsa.getModulus();
        BigInteger exponent = rsa.getPublicExponent();

        int words = (modulus.bitLength() + 31) / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = modulus.modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(modulus);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        writeIntLE(bos, words);
        writeIntLE(bos, n0inv.intValue());
        writeBigIntegerLE(bos, modulus, words);
        writeBigIntegerLE(bos, rr, words);
        writeIntLE(bos, exponent.intValue());

        String encoded = base64(bos.toByteArray());
        String payload = encoded + " com.hz.adbperm@tv";
        return (payload + "\0").getBytes("UTF-8");
    }

    private static void writeBigIntegerLE(ByteArrayOutputStream bos, BigInteger value, int words) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[words * 4];
        int copy = Math.min(raw.length, fixed.length);
        for (int i = 0; i < copy; i++) {
            fixed[i] = raw[raw.length - 1 - i];
        }
        bos.write(fixed, 0, fixed.length);
    }

    private static void writeIntLE(ByteArrayOutputStream bos, int value) {
        bos.write(value & 0xff);
        bos.write((value >> 8) & 0xff);
        bos.write((value >> 16) & 0xff);
        bos.write((value >> 24) & 0xff);
    }

    private static String base64(byte[] data) {
        final char[] table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            sb.append(table[(n >> 18) & 63]).append(table[(n >> 12) & 63])
              .append(table[(n >> 6) & 63]).append(table[n & 63]);
            i += 3;
        }
        int left = data.length - i;
        if (left == 1) {
            int n = (data[i] & 0xff) << 16;
            sb.append(table[(n >> 18) & 63]).append(table[(n >> 12) & 63]).append("==");
        } else if (left == 2) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            sb.append(table[(n >> 18) & 63]).append(table[(n >> 12) & 63])
              .append(table[(n >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    private void write(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int checksum = 0;
        for (byte b : data) {
            checksum += b & 0xff;
        }
        writeInt(out, cmd);
        writeInt(out, arg0);
        writeInt(out, arg1);
        writeInt(out, data.length);
        writeInt(out, checksum);
        writeInt(out, cmd ^ 0xffffffff);
        out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        Msg m = new Msg();
        int cmd = readInt(in);
        int arg0 = readInt(in);
        int arg1 = readInt(in);
        int length = readInt(in);
        readInt(in);
        int magic = readInt(in);
        if ((cmd ^ 0xffffffff) != magic) {
            throw new IOException("ADB 报文校验失败");
        }
        if (length < 0 || length > 4 * 1024 * 1024) {
            throw new IOException("ADB 报文长度异常");
        }
        m.cmd = cmd;
        m.arg0 = arg0;
        m.arg1 = arg1;
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    private static void writeInt(OutputStream os, int value) throws IOException {
        os.write(value & 0xff);
        os.write((value >> 8) & 0xff);
        os.write((value >> 16) & 0xff);
        os.write((value >> 24) & 0xff);
    }

    private static int readInt(InputStream is) throws IOException {
        int b0 = is.read();
        int b1 = is.read();
        int b2 = is.read();
        int b3 = is.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new IOException("ADB 连接已断开");
        }
        return (b0 & 0xff) | ((b1 & 0xff) << 8) | ((b2 & 0xff) << 16) | ((b3 & 0xff) << 24);
    }

    private static byte[] readAll(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            fis.close();
        }
    }
}
