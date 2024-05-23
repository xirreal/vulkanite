package me.cortex.vulkanite.lib.other.sync;

import com.sun.jna.Pointer;
import com.sun.jna.platform.linux.LibC;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.VRef;
import org.lwjgl.vulkan.VkDevice;

import static org.lwjgl.opengl.EXTSemaphore.*;

public class VGSemaphore extends VSemaphore {
    public final int glSemaphore;
    private final long handleDescriptor;

    protected VGSemaphore(VkDevice device, long semaphore, int glSemaphore, long handleDescriptor) {
        super(device, semaphore);
        this.glSemaphore = glSemaphore;
        this.handleDescriptor = handleDescriptor;
    }

    public static VRef<VGSemaphore> create(VkDevice device, long semaphore, int glSemaphore, long handleDescriptor) {
        return new VRef<>(new VGSemaphore(device, semaphore, glSemaphore, handleDescriptor));
    }

    @Override
    protected void free() {
        glDeleteSemaphoresEXT(this.glSemaphore);
        if (Vulkanite.IS_WINDOWS) {
            if (!Kernel32.INSTANCE.CloseHandle(new WinNT.HANDLE(new Pointer(handleDescriptor)))) {
                int error = Kernel32.INSTANCE.GetLastError();
                System.err.println("STATE MIGHT BE BROKEN! Failed to close handle: " + error);
                throw new IllegalStateException();
            }
        } else {
            int code = 0;
            if ((code = LibC.INSTANCE.close((int) handleDescriptor)) != 0) {
                System.err.println("STATE MIGHT BE BROKEN! Failed to close FD: " + code);
                throw new IllegalStateException();
            }
        }
        super.free();
    }

    //Note: dstLayout is for the textures
    public void glSignal(int[] buffers, int[] textures, int[] dstLayouts) {
        glSignalSemaphoreEXT(glSemaphore, buffers, textures, dstLayouts);
    }

    //Note: srcLayout is for the textures
    public void glWait(int[] buffers, int[] textures, int[] srcLayouts) {
        glWaitSemaphoreEXT(glSemaphore, buffers, textures, srcLayouts);
    }
}
