package com.example.tb.authentication.service.telegram;

import com.example.tb.authentication.service.UserRegistrationService;
import com.example.tb.model.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TelegramServiceImpl extends TelegramLongPollingBot {
    private static final Logger logger = LoggerFactory.getLogger(TelegramServiceImpl.class);
    public static final String botToken = "7604740715:AAGnrNxu0hnnJ8JdtEBin1R3S_yE6GiHGHI";
    public static final String botUsername = "telepasskhbot";
    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }
    @Autowired
    private UserRegistrationService userRegistrationService;

    // Registration state tracking


    // In-memory user registration tracking (replace with distributed cache in production)
    private Map<Long, RegistrationContext> registrationContexts = new ConcurrentHashMap<>();

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                long chatId = update.getMessage().getChatId();
                String messageText = update.getMessage().getText();

                // Handle registration command
                if ("/register".equals(messageText)) {
                    startRegistration(chatId);
                } else {
                    // Process registration steps
                    processRegistrationStep(chatId, messageText);
                }
            }
        } catch (Exception e) {
            logger.error("Error in registration process", e);
        }
    }

    private void startRegistration(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Welcome to registration! Let's collect your details.\n\nPlease enter your Full Name:");

        // Initialize registration context
        registrationContexts.put(chatId, new RegistrationContext());

        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.info("Error starting registration", e);
        }
    }

    private void processRegistrationStep(long chatId, String messageText) {
        RegistrationContext context = registrationContexts.get(chatId);
        if (context == null) {
            startRegistration(chatId);
            return;
        }

        try {
            switch (context.getCurrentStep()) {
                case START:
                    // Validate and set full name
                    if (isValidName(messageText)) {
                        context.getUser().setFullName(messageText);
                        context.setCurrentStep(RegistrationContext.RegistrationStep.PHONE_NUMBER);
                        sendMessage(chatId, "Great! Now please send your Phone Number (including country code, e.g., +1234567890):");
                    } else {
                        sendMessage(chatId, "Invalid name. Please enter a valid full name.");
                    }
                    break;

                case PHONE_NUMBER:
                    if (isValidPhoneNumber(messageText)) {
                        context.getUser().setPhoneNumber(messageText);
                        context.setCurrentStep(RegistrationContext.RegistrationStep.GENDER);
                        sendGenderKeyboard(chatId);
                    } else {
                        sendMessage(chatId, "Invalid phone number. Please enter a valid phone number with country code.");
                    }
                    break;

                case GENDER:
                    if (isValidGender(messageText)) {
                        context.getUser().setGender(User.Gender.valueOf(messageText.toUpperCase()));
                        context.setCurrentStep(RegistrationContext.RegistrationStep.DATE_OF_BIRTH);
                        sendMessage(chatId, "Please enter your Date of Birth (YYYY-MM-DD format):");
                    } else {
                        sendGenderKeyboard(chatId);
                    }
                    break;

                case DATE_OF_BIRTH:
                    if (isValidDateOfBirth(messageText)) {
                        context.getUser().setDateOfBirth(LocalDate.parse(messageText));
                        context.setCurrentStep(RegistrationContext.RegistrationStep.ADDRESS);
                        sendMessage(chatId, "Enter your full address:");
                    } else {
                        sendMessage(chatId, "Invalid date format. Please use YYYY-MM-DD format.");
                    }
                    break;

                case ADDRESS:
                    if (isValidAddress(messageText)) {
                        context.getUser().setAddress(messageText);
                        context.setCurrentStep(RegistrationContext.RegistrationStep.EMAIL);
                        sendMessage(chatId, "Please enter your email address:");
                    } else {
                        sendMessage(chatId, "Invalid address. Please provide a valid address.");
                    }
                    break;

                case EMAIL:
                    if (isValidEmail(messageText)) {
                        context.getUser().setEmail(messageText);
                        context.setCurrentStep(RegistrationContext.RegistrationStep.OCCUPATION);
                        sendMessage(chatId, "What is your occupation?");
                    } else {
                        sendMessage(chatId, "Invalid email address. Please enter a valid email.");
                    }
                    break;

                case OCCUPATION:
                    if (isValidOccupation(messageText)) {
                        context.getUser().setOccupation(messageText);

                        // Complete registration
                        User registeredUser = userRegistrationService.registerUser(context.getUser());

                        // Generate QR Code
                        String qrCodeBase64 = userRegistrationService.generateQRCode(registeredUser.getRegistrationToken());

                        // Send QR Code and completion message
                        sendQRCodeAndCompleteRegistration(chatId, qrCodeBase64);

                        // Remove context
                        registrationContexts.remove(chatId);
                    } else {
                        sendMessage(chatId, "Invalid occupation. Please provide your occupation.");
                    }
                    break;
            }
        } catch (Exception e) {
            logger.error("Error in registration step", e);
            sendMessage(chatId, "An error occurred. Please start registration again with /register");
        }
    }

    private void sendQRCodeAndCompleteRegistration(long chatId, String qrCodeBase64) throws TelegramApiException {
        // Decode Base64 QR Code
        byte[] qrCodeBytes = Base64.getDecoder().decode(qrCodeBase64);

        // Send QR Code as photo
        SendPhoto sendPhoto = new SendPhoto();
        sendPhoto.setChatId(chatId);
        sendPhoto.setPhoto(new InputFile(new ByteArrayInputStream(qrCodeBytes), "registration_qr.png"));
        sendPhoto.setCaption("Registration Successful! Use this QR code for event access. Your unique token is embedded in the QR code.");

        // Send the QR code
        execute(sendPhoto);
    }

    private void sendGenderKeyboard(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText("Select your gender:");

        // Create custom keyboard
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();

        row.add(new KeyboardButton("Male"));
        row.add(new KeyboardButton("Female"));
        row.add(new KeyboardButton("Other"));

        keyboard.add(row);
        keyboardMarkup.setKeyboard(keyboard);
        keyboardMarkup.setOneTimeKeyboard(true);

        message.setReplyMarkup(keyboardMarkup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Error sending gender keyboard", e);
        }
    }

    // Validation methods (simplified, you should enhance these)
    private boolean isValidName(String name) {
        return name != null && name.trim().length() >= 2 && name.trim().length() <= 100;
    }

    private boolean isValidPhoneNumber(String phoneNumber) {
        return phoneNumber != null && phoneNumber.matches("^\\+?[1-9]\\d{1,14}$");
    }

    private boolean isValidGender(String gender) {
        return gender != null &&
                (gender.equalsIgnoreCase("male") ||
                        gender.equalsIgnoreCase("female") ||
                        gender.equalsIgnoreCase("other"));
    }

    private boolean isValidDateOfBirth(String dob) {
        try {
            LocalDate parsedDate = LocalDate.parse(dob);
            return parsedDate.isBefore(LocalDate.now().minusYears(13)); // At least 13 years old
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isValidAddress(String address) {
        return address != null && address.trim().length() >= 5 && address.trim().length() <= 255;
    }

    private boolean isValidEmail(String email) {
        return email != null && email.matches("^[A-Za-z0-9+_.-]+@(.+)$");
    }

    private boolean isValidOccupation(String occupation) {
        return occupation != null && occupation.trim().length() >= 2 && occupation.trim().length() <= 100;
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Error sending message", e);
        }
    }

}