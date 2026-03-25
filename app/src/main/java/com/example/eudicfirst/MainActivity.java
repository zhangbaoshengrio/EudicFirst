package com.example.eudicfirst;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("EudicFirst 模块已安装\n请在 LSPosed 管理器中启用并重启手机");
        tv.setPadding(48, 48, 48, 48);
        tv.setTextSize(18);
        setContentView(tv);
    }
}
