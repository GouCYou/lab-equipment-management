import { reactive } from "vue";
type Options = {
  inputValue?: string;
  inputLabel?: string;
  singleLine?: boolean;
  confirmButtonText?: string;
  cancelButtonText?: string;
  inputValidator?: (value: string) => boolean | string;
  type?: string;
};
export const dialogState = reactive({
  open: false,
  title: "",
  message: "",
  prompt: false,
  singleLine: false,
  inputLabel: "填写内容",
  value: "",
  error: "",
  confirmText: "确认",
  cancelText: "取消",
});
let complete: ((value: { value: string }) => void) | undefined,
  cancel: ((reason: string) => void) | undefined,
  validator: Options["inputValidator"];
function ask(
  message: string,
  title: string,
  options: Options = {},
  prompt = false,
): Promise<{ value: string }> {
  if (dialogState.open) finishDialog(false);
  Object.assign(dialogState, {
    open: true,
    title,
    message,
    prompt,
    singleLine: !!options.singleLine,
    inputLabel: options.inputLabel || "填写内容",
    value: options.inputValue || "",
    error: "",
    confirmText: options.confirmButtonText || "确认",
    cancelText: options.cancelButtonText || "取消",
  });
  validator = options.inputValidator;
  return new Promise((resolve, reject) => {
    complete = resolve;
    cancel = reject;
  });
}
export function finishDialog(accepted: boolean) {
  if (accepted && validator) {
    const result = validator(dialogState.value);
    if (result !== true) {
      dialogState.error = typeof result === "string" ? result : "请填写内容";
      return;
    }
  }
  dialogState.open = false;
  const resolve = complete,
    reject = cancel;
  complete = undefined;
  cancel = undefined;
  if (accepted) resolve?.({ value: dialogState.value });
  else reject?.("cancel");
}
export const dialogs = {
  confirm: (message: string, title: string, options?: Options) =>
    ask(message, title, options),
  prompt: (message: string, title: string, options?: Options) =>
    ask(message, title, options, true),
};
