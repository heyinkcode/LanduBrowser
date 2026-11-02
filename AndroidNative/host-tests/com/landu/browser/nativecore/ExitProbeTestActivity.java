package com.landu.browser.nativecore;

/** Internal emulator-only harness, never part of the browser overlay inventory. */
public final class ExitProbeTestActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView output=new android.widget.TextView(this);output.setText("Running internal exit query checks…");setContentView(output);
        try { java.nio.file.Files.deleteIfExists(getFilesDir().toPath().resolve("result.log")); }
        catch(java.io.IOException ignored) { }
        new Thread(()->{
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            java.io.PrintStream original=System.out;
            try(java.io.PrintStream capture=new java.io.PrintStream(bytes,true,"UTF-8")) {
                System.setOut(capture);
                android.net.ConnectivityManager manager=getSystemService(android.net.ConnectivityManager.class);
                capture.println("{\"normalApplicationProcess\":true,\"hasActiveNetwork\":"+(manager.getActiveNetwork()!=null)+"}");
                AndroidExitLookupTest.main(new String[]{"live"});
                AndroidRegionalLaunchTest.run(this);
            }catch(Throwable error){error.printStackTrace(new java.io.PrintStream(bytes));}
            finally{System.setOut(original);}
            String result=new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
            try{java.nio.file.Files.write(getFilesDir().toPath().resolve("result.log"),bytes.toByteArray());}catch(java.io.IOException ignored){}
            runOnUiThread(()->output.setText(result));
        },"internal-exit-probe").start();
    }
}
