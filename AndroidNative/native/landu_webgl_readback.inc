// Copyright 2026 Landu Browser contributors.
// SPDX-License-Identifier: BSD-3-Clause
// Included once inside namespace blink by webgl_rendering_context_base.cc.
bool WebGLRenderingContextBase::ReadPixelsWithLanduNoise(
    GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type,
    void* array_destination, GLuint pack_buffer, GLintptr buffer_offset) {
  const auto seed = LanduCanvasSeed();
  if (!seed || width <= 0 || height <= 0) return false;

  auto* gl = ContextGL();
  const auto preserve_driver_errors = [this, gl]() {
    bool found = false;
    for (unsigned count = 0; count < 32; ++count) {
      const GLenum error = gl->GetError();
      if (error == GL_NO_ERROR) return found;
      found = true;
      if (!synthetic_errors_.Contains(error)) synthetic_errors_.push_back(error);
      if (error == 0x0507 || error == GC3D_CONTEXT_LOST_WEBGL) return true;
    }
    return true;
  };
  // Keep errors from earlier application calls visible, including duplicate
  // error flags that would otherwise be split between client and GL queues.
  preserve_driver_errors();
  if (isContextLost()) return true;

  const auto pack = GetPackPixelStoreParams();
  unsigned components = 0, component_bytes = 0;
  if (!WebGLImageConversion::ComputeFormatAndTypeParameters(
          format, type, &components, &component_bytes)) {
    SynthesizeGLError(GL_INVALID_ENUM, "readPixels", "invalid pixel layout");
    return true;
  }
  if (!landu::ValidWebGLPackRange(width, pack.row_length, pack.skip_pixels) ||
      (pack_buffer && !landu::ValidWebGLPackOffset(buffer_offset, component_bytes)) ||
      (!pack_buffer && !array_destination)) {
    SynthesizeGLError(GL_INVALID_OPERATION, "readPixels", "invalid pack layout or offset");
    return true;
  }
  const auto layout = landu::MakeWebGLPackLayout(
      width, height, components * component_bytes, pack.alignment,
      pack.row_length, pack.skip_pixels, pack.skip_rows);
  unsigned original_size = 0, original_skip = 0;
  const GLenum layout_error = WebGLImageConversion::ComputeImageSizeInBytes(
      format, type, width, height, 1, pack, &original_size, nullptr, &original_skip);
  if (!layout || layout_error != GL_NO_ERROR ||
      layout->end_bytes != uint64_t(original_size) + original_skip ||
      layout->snapshot_bytes > kMaximumSupportedArrayBufferSize) {
    SynthesizeGLError(GL_INVALID_VALUE, "readPixels", "pixel layout is too large");
    return true;
  }
  if (pack_buffer) {
    // ANGLE validates mapped buffers and transform-feedback conflicts even
    // for a zero-area read. Preserve those original access restrictions
    // before temporarily unbinding the PBO, without writing raw pixels to it.
    gl->ReadPixels(x, y, 0, 0, format, type, reinterpret_cast<void*>(buffer_offset));
    if (preserve_driver_errors() || isContextLost()) return true;
  }
  void* allocated = nullptr;
  if (!base::UncheckedCalloc(1, layout->snapshot_bytes, &allocated) || !allocated) {
    SynthesizeGLError(GL_OUT_OF_MEMORY, "readPixels", "private readback allocation failed");
    return true;
  }
  std::unique_ptr<uint8_t, base::UncheckedFreeDeleter> owned(
      static_cast<uint8_t*>(allocated));
  // SAFETY: UncheckedCalloc returned exactly snapshot_bytes owned bytes.
  auto snapshot = UNSAFE_BUFFERS(std::span(owned.get(), layout->snapshot_bytes));
  const bool webgl2 = IsWebGL2();
  {
    base::ScopedClosureRunner restore(base::BindOnce([](
        gpu::gles2::GLES2Interface* gl, WebGLImageConversion::PixelStoreParams pack,
        GLuint pack_buffer, bool webgl2) {
      gl->PixelStorei(GL_PACK_ALIGNMENT, pack.alignment);
      if (webgl2) {
        gl->PixelStorei(GL_PACK_ROW_LENGTH, pack.row_length);
        gl->PixelStorei(GL_PACK_SKIP_PIXELS, pack.skip_pixels);
        gl->PixelStorei(GL_PACK_SKIP_ROWS, pack.skip_rows);
      }
      if (pack_buffer) gl->BindBuffer(GL_PIXEL_PACK_BUFFER, pack_buffer);
    }, base::Unretained(gl), pack, pack_buffer, webgl2));
    if (pack_buffer) gl->BindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    gl->PixelStorei(GL_PACK_ALIGNMENT, 1);
    if (webgl2) {
      gl->PixelStorei(GL_PACK_ROW_LENGTH, 0);
      gl->PixelStorei(GL_PACK_SKIP_PIXELS, 0);
      gl->PixelStorei(GL_PACK_SKIP_ROWS, 0);
    }
    // Never put raw pixels in the application PBO before protection. Reading
    // privately also preserves row padding/skip bytes. Original invalid PACK
    // combinations and PBO alignment are checked before entering this scope.
    gl->ReadPixels(x, y, width, height, format, type, snapshot.data());
  }
  if (preserve_driver_errors() || isContextLost() ||
      gl->GetGraphicsResetStatusKHR() != GL_NO_ERROR) return true;

  const auto description = landu::DescribeWebGLPixels(format, type);
  const bool default_framebuffer = !GetReadFramebufferBinding();
  const int64_t origin_y = default_framebuffer
      ? int64_t(drawingBufferHeight()) - 1 - y : int64_t(y);
  if (!description || !landu::NoiseWebGLPixels(snapshot, width, height,
          *description, *seed, x, origin_y, default_framebuffer ? -1 : 1)) {
    SynthesizeGLError(GL_INVALID_OPERATION, "readPixels", "unsupported protected pixel layout");
    return true;
  }
  if (pack_buffer) {
    for (size_t row = 0; row < layout->height; ++row) {
      auto input = snapshot.subspan(row * layout->row_size, layout->row_size);
      gl->BufferSubData(GL_PIXEL_PACK_BUFFER,
          buffer_offset + layout->skip_bytes + row * layout->row_bytes,
          layout->row_size, input.data());
    }
    preserve_driver_errors();
  } else {
    // SAFETY: ValidateReadPixelsFuncParameters validated the destination after
    // its typed-array element offset, including this identical pack layout.
    auto destination = UNSAFE_BUFFERS(std::span(
        static_cast<uint8_t*>(array_destination), layout->end_bytes));
    CHECK(landu::CopyWebGLPackedRows(snapshot, destination, *layout));
  }
  return true;
}
