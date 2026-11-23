const KEY = 'dsports.cricket.shotDirection';

/** Off by default and never required. Kept per device; if storage is unavailable the setting is simply off. */
export const readShotSetting = (): boolean => {
  try {
    return window.localStorage.getItem(KEY) === 'on';
  } catch {
    return false;
  }
};

export const writeShotSetting = (on: boolean): void => {
  try {
    window.localStorage.setItem(KEY, on ? 'on' : 'off');
  } catch {
    // The setting just does not stick.
  }
};
