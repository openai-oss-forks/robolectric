package org.robolectric.shadows;

import static android.os.Build.VERSION_CODES.BAKLAVA;
import static android.os.Build.VERSION_CODES.N_MR1;
import static android.os.Build.VERSION_CODES.R;
import static android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM;
import static com.google.common.truth.Truth.assertThat;

import android.view.ViewRootImpl;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

/** Checks registered reset compatibility across SDKs with different hardware-rendering APIs. */
@RunWith(RobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = {N_MR1, R, VANILLA_ICE_CREAM, BAKLAVA})
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ShadowViewRootImplResetTest {
  @Test
  public void reset_clearsViewRootStateAcrossGraphicsApiBoundary() {
    ArrayList<Runnable> handlers = new ArrayList<>();
    handlers.add(() -> {});
    ReflectionHelpers.setStaticField(ViewRootImpl.class, "sFirstDrawHandlers", handlers);
    ReflectionHelpers.setStaticField(ViewRootImpl.class, "sFirstDrawComplete", true);

    ShadowViewRootImpl.reset();

    assertThat(
            ReflectionHelpers.<ArrayList<Runnable>>getStaticField(
                ViewRootImpl.class, "sFirstDrawHandlers"))
        .isEmpty();
    assertThat(ReflectionHelpers.<Boolean>getStaticField(ViewRootImpl.class, "sFirstDrawComplete"))
        .isFalse();
  }
}
