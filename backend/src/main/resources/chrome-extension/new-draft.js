// Only extension-created new-post tabs skip restoring a previous temporary draft.
if (location.hash === '#issuedesk-new-draft') {
  const originalConfirm = window.confirm;
  window.confirm = function(message) {
    if (/(이어서|이어\s*쓰|불러\s*오|불러올|계속.*작성)/.test(String(message))) return false;
    return originalConfirm.call(window, message);
  };
}
