/*
 * Copyright 2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import java.util.List;

import javax.annotation.Nullable;

import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.preference.PinRetryController;
import de.schildbach.wallet.ui.send.DecryptSeedTask;
import de.schildbach.wallet.ui.send.DeriveKeyTask;
import de.schildbach.wallet.util.KeyboardUtil;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public class BackupWalletToSeedDialogFragment extends DialogFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String FRAGMENT_TAG = BackupWalletToSeedDialogFragment.class.getName();

    public static void show(final FragmentManager fm) {
        final BackupWalletToSeedDialogFragment newFragment = new BackupWalletToSeedDialogFragment();
        newFragment.isUpgrading = false;
        newFragment.onDismissListener = null;
        newFragment.show(fm, FRAGMENT_TAG);
    }

    public static void show(final FragmentManager fm, boolean isUpgrading, DialogInterface.OnDismissListener onDismissListener) {
        final BackupWalletToSeedDialogFragment newFragment = new BackupWalletToSeedDialogFragment();
        newFragment.isUpgrading = isUpgrading;
        newFragment.onDismissListener = onDismissListener;
        newFragment.show(fm, FRAGMENT_TAG);
    }

    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Wallet wallet;
    private PinRetryController pinRetryController;
    private Configuration config;
    private boolean isUpgrading;
    private DialogInterface.OnDismissListener onDismissListener;

    @Nullable
    private AlertDialog dialog;

    private TextView seedView;
    private View privateKeyPasswordViewGroup;
    private EditText privateKeyPasswordView;
    private TextView privateKeyBadPasswordView;
    private View seedViewGroup;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private CheckBox showView;
    private CheckBox writtenDown;
    private Button showMnemonicSeedButton;

    private static final Logger log = LoggerFactory.getLogger(BackupWalletToSeedDialogFragment.class);

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractWalletActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.wallet = application.getWallet();
        this.pinRetryController = new PinRetryController(activity);
        this.config = application.getConfiguration();
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final View view = LayoutInflater.from(activity).inflate(R.layout.backup_wallet_to_seed_dialog, null);

        seedView = (TextView) view.findViewById(R.id.backup_wallet_dialog_seed);
        privateKeyPasswordViewGroup = view.findViewById(R.id.backup_wallet_seed_private_key_password_group);
        privateKeyPasswordView = (EditText) view.findViewById(R.id.backup_wallet_seed_private_key_password);
        privateKeyBadPasswordView = (TextView) view.findViewById(R.id.backup_wallet_seed_private_key_bad_password);
        showMnemonicSeedButton = (Button) view.findViewById(R.id.backup_wallet_seed_private_key_enter);
        seedViewGroup = view.findViewById(R.id.backup_wallet_seed_group);
        writtenDown = (CheckBox)view.findViewById(R.id.backup_wallet_seed_private_key_written_down);

        privateKeyPasswordViewGroup.setVisibility(wallet.isEncrypted() ? View.VISIBLE : View.GONE);

        privateKeyPasswordView.addTextChangedListener(privateKeyPasswordListener);
        showMnemonicSeedButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                handleDecryptPIN();
            }
        });
        if (wallet.isEncrypted()) {
            backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
            showPasswordViewGroup(true);
            updateView(false);
        } else {
            showMnemonicSeed(wallet.getActiveKeyChain().getSeed());
            updateView(true);
        }

        final DialogBuilder builder = new DialogBuilder(activity);
        builder.setTitle(R.string.export_keys_dialog_title);
        builder.setView(view);
        builder.setPositiveButton(R.string.button_ok, null);
        builder.setCancelable(false);

        return builder.create();
    }

    @Override
    public void onResume() {
        super.onResume();
        config.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        config.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onDismiss(final DialogInterface dialog) {
        this.dialog = null;
        if(writtenDown.isChecked())
            config.disarmBackupSeedReminder();
        privateKeyPasswordView.removeTextChangedListener(privateKeyPasswordListener);
        if(onDismissListener != null)
            onDismissListener.onDismiss(dialog);
        super.onDismiss(dialog);
    }

    private final TextWatcher privateKeyPasswordListener = new TextWatcher() {
        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            privateKeyBadPasswordView.setVisibility(View.INVISIBLE);
            showMnemonicSeedButton.setEnabled(true);
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void afterTextChanged(final Editable s) {
        }
    };

    private void showMnemonicSeed(final DeterministicSeed seed) {
        StringBuilder wordlist = new StringBuilder(255);

        List<String> code = seed.getMnemonicCode();


        for (String word : code) {
            wordlist.append(word + " ");
        }
        seedView.setText(wordlist);
    }

    private void handleDecryptPIN() {
        if (wallet.isEncrypted()) {

            if (pinRetryController.isLocked()) {
                return;
            }

            showMnemonicSeedButton.setEnabled(false);
            showMnemonicSeedButton.setText(getText(R.string.encrypt_keys_dialog_state_decrypting));
            privateKeyPasswordView.setEnabled(false);

            final String pin = privateKeyPasswordView.getText().toString().trim();

            new DeriveKeyTask(backgroundHandler, application.scryptIterationsTarget()) {
                @Override
                protected void onSuccess(final KeyParameter encryptionKey, final boolean wasChanged) {
                    privateKeyBadPasswordView.setVisibility(View.INVISIBLE);
                    handleDecryptSeed(encryptionKey, pin);
                }
            }.deriveKey(wallet, pin);

        } else {

        }
    }

    private void handleDecryptSeed(final KeyParameter encryptionKey, final String pin) {
        if (wallet.isEncrypted()) {
            if (pinRetryController.isLocked()) {
                return;
            }
            showMnemonicSeedButton.setEnabled(false);
            new DecryptSeedTask(backgroundHandler) {
                @Override
                protected void onSuccess(final DeterministicSeed seed) {
                    pinRetryController.successfulAttempt();
                    showPasswordViewGroup(false);
                    showMnemonicSeed(seed);
                    updateView(true);
                }

                protected void onBadPassphrase() {
                    pinRetryController.failedAttempt(pin);
                    privateKeyBadPasswordView.setVisibility(View.VISIBLE);
                    privateKeyBadPasswordView.setText(getString(R.string.wallet_lock_wrong_pin,
                            pinRetryController.getRemainingAttemptsMessage()));
                    privateKeyPasswordView.setEnabled(true);
                    privateKeyPasswordView.requestFocus();
                    showMnemonicSeedButton.setText(getText(R.string.backup_wallet_to_seed_show_recovery_phrase));
                    updateView(false);
                }
            }.decryptSeed(wallet.getActiveKeyChain().getSeed(), wallet.getKeyCrypter(), encryptionKey);

        } else {

        }
    }

    private void showPasswordViewGroup(boolean show) {
        if (show) {
            seedViewGroup.setVisibility(View.GONE);
            privateKeyPasswordView.post(new Runnable() {
                @Override
                public void run() {
                    if (isAdded()) {
                        KeyboardUtil.showSoftKeyboard(getActivity(), privateKeyPasswordView);
                    }
                }
            });
        } else {
            seedViewGroup.setVisibility(View.VISIBLE);
            privateKeyPasswordViewGroup.setVisibility(View.GONE);
            KeyboardUtil.hideKeyboard(getActivity(), privateKeyPasswordView);
        }
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_REMIND_BACKUP_SEED.equals(key))
            updateView(false);
    }

    private void updateView(boolean isUnlocked) {
        if(!config.remindBackupSeed() || isUpgrading)
            writtenDown.setVisibility(View.GONE);
        else writtenDown.setVisibility(isUnlocked ? View.VISIBLE : View.GONE);

    }
}
