package com.landu.browser.nativecore;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public final class LanduLinkActivity extends Activity {
    public static final String EXTRA_LINK = "com.landu.browser.environment.EXTERNAL_LINK";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent request = getIntent();
        try {
            String address;
            if (Intent.ACTION_VIEW.equals(request.getAction())) {
                address = request.getDataString();
            } else if (Intent.ACTION_SEND.equals(request.getAction())) {
                address = request.getStringExtra(Intent.EXTRA_TEXT);
            } else {
                throw new IllegalArgumentException("请先在岚渡中选择浏览器环境");
            }
            Intent manager = new Intent(this, LanduManagerActivity.class);
            manager.putExtra(EXTRA_LINK, ExternalNavigation.webAddress(address));
            manager.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(manager);
        } catch (RuntimeException error) {
            Toast.makeText(this, "此入口支持 http/https 网页链接，请先打开岚渡环境管理", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, LanduManagerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }
        finish();
    }
}
