/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.print.cts;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.os.CancellationSignal;
import android.os.CancellationSignal.OnCancelListener;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.Margins;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentAdapter.LayoutResultCallback;
import android.print.PrintDocumentAdapter.WriteResultCallback;
import android.print.PrintDocumentInfo;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.print.cts.services.FirstPrintService;
import android.print.cts.services.PrintServiceCallbacks;
import android.print.cts.services.PrinterDiscoverySessionCallbacks;
import android.print.cts.services.SecondPrintService;
import android.print.cts.services.StubbablePrinterDiscoverySession;
import android.printservice.PrintJob;
import android.printservice.PrintService;

import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This test verifies that the system respects the {@link PrintDocumentAdapter}
 * contract and invokes all callbacks as expected.
 */
public class PrintDocumentAdapterContractTest extends BasePrintTest {

    public void testNoPrintOptionsOrPrinterChange() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        final PrintAttributes[] printAttributes = new PrintAttributes[1];

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                printAttributes[0] = (PrintAttributes) invocation.getArguments()[1];
                LayoutResultCallback callback = (LayoutResultCallback) invocation.getArguments()[3];
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(PRINT_JOB_NAME)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(2)
                        .build();
                callback.onLayoutFinished(info, false);
                // Mark layout was called.
                onLayoutCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                PageRange[] pages = (PageRange[]) args[0];
                ParcelFileDescriptor fd = (ParcelFileDescriptor) args[1];
                WriteResultCallback callback = (WriteResultCallback) args[3];
                writeBlankPages(printAttributes[0], fd, pages[0].getStart(), pages[0].getEnd());
                fd.close();
                callback.onWriteFinished(pages);
                // Mark write was called.
                onWriteCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for write.
        waitForWriteAdapterCallback();

        // Select the second printer.
        selectPrinter("Second printer");

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(2);

        // Click the print button.
        clickPrintButton();

        // Wait for finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // We always ask for the the first fifty pages for preview.
        PageRange[] firstPages = new PageRange[] {new PageRange(0, 1)};
        inOrder.verify(adapter).onWrite(eq(firstPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // We selected the second printer which does not support the media
        // size that was selected, so a new layout happens as the size changed.
        // Since we passed false to the layout callback meaning that the content
        // didn't change, there shouldn't be a next call to write.
        PrintAttributes secondOldAttributes = firstNewAttributes;
        PrintAttributes secondNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.ISO_A3)
                .setResolution(new Resolution("300x300", "300x300", 300, 300))
                .setMinMargins(Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, secondOldAttributes, secondNewAttributes, true);

        // When print is pressed we ask for a layout which is *not* for preview.
        verifyLayoutCall(inOrder, adapter, secondNewAttributes, secondNewAttributes, false);

        // When print is pressed we ask for all selected pages but we got
        // them when asking for the ones for a preview, and the adapter does
        // not report a content change. Hence, there is nothing to write.

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testNoPrintOptionsOrPrinterChangeCanceled() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        final PrintAttributes[] printAttributes = new PrintAttributes[1];

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                printAttributes[0] = (PrintAttributes) invocation.getArguments()[1];
                LayoutResultCallback callback = (LayoutResultCallback)
                        invocation.getArguments()[3];
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(PRINT_JOB_NAME)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build();
                callback.onLayoutFinished(info, false);
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                PageRange[] pages = (PageRange[]) args[0];
                ParcelFileDescriptor fd = (ParcelFileDescriptor) args[1];
                WriteResultCallback callback = (WriteResultCallback) args[3];
                writeBlankPages(printAttributes[0], fd, pages[0].getStart(), pages[0].getEnd());
                fd.close();
                callback.onWriteFinished(pages);
                // Mark write was called.
                onWriteCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for write.
        waitForWriteAdapterCallback();

        // Cancel the printing.
        getUiDevice().pressBack(); // wakes up the device.
        getUiDevice().pressBack();

        // Wait for finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // We always ask for the the first fifty pages for preview.
        PageRange[] firstPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(firstPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testPrintOptionsChangeAndNoPrinterChange() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        final PrintAttributes[] printAttributes = new PrintAttributes[1];

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                printAttributes[0] = (PrintAttributes) invocation.getArguments()[1];
                LayoutResultCallback callback = (LayoutResultCallback)
                        invocation.getArguments()[3];
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(PRINT_JOB_NAME)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build();
                callback.onLayoutFinished(info, false);
                // Mark layout was called.
                onLayoutCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                PageRange[] pages = (PageRange[]) args[0];
                ParcelFileDescriptor fd = (ParcelFileDescriptor) args[1];
                WriteResultCallback callback = (WriteResultCallback) args[3];
                writeBlankPages(printAttributes[0], fd, pages[0].getStart(), pages[0].getEnd());
                fd.close();
                callback.onWriteFinished(pages);
                // Mark write was called.
                onWriteCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for write.
        waitForWriteAdapterCallback();

        // Open the print options.
        openPrintOptions();

        // Select the second printer.
        selectPrinter("Second printer");

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(2);

        // Change the orientation.
        changeOrientation("Landscape");

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(3);

        // Change the media size.
        changeMediaSize("ISO A4");

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(4);

        // Change the color.
        changeColor("Black & White");

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(5);

        // Click the print button.
        clickPrintButton();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // We always ask for the the first fifty pages for preview.
        PageRange[] firstPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(firstPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // We selected the second printer which does not support the media
        // size that was selected, so a new layout happens as the size changed.
        // Since we passed false to the layout callback meaning that the content
        // didn't change, there shouldn't be a next call to write.
        PrintAttributes secondOldAttributes = firstNewAttributes;
        PrintAttributes secondNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.ISO_A3)
                .setResolution(new Resolution("300x300", "300x300", 300, 300))
                .setMinMargins(Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, secondOldAttributes, secondNewAttributes, true);

        // We changed the orientation which triggers a layout. Since we passed
        // false to the layout callback meaning that the content didn't change,
        // there shouldn't be a next call to write.
        PrintAttributes thirdOldAttributes = secondNewAttributes;
        PrintAttributes thirdNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.ISO_A3.asLandscape())
                .setResolution(new Resolution("300x300", "300x300", 300, 300))
                .setMinMargins(Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, thirdOldAttributes, thirdNewAttributes, true);

        // We changed the media size which triggers a layout. Since we passed
        // false to the layout callback meaning that the content didn't change,
        // there shouldn't be a next call to write.
        PrintAttributes fourthOldAttributes = thirdNewAttributes;
        PrintAttributes fourthNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.ISO_A4.asLandscape())
                .setResolution(new Resolution("300x300", "300x300", 300, 300))
                .setMinMargins(Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, fourthOldAttributes, fourthNewAttributes, true);

        // We changed the color which triggers a layout. Since we passed
        // false to the layout callback meaning that the content didn't change,
        // there shouldn't be a next call to write.
        PrintAttributes fifthOldAttributes = fourthNewAttributes;
        PrintAttributes fifthNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.ISO_A4.asLandscape())
                .setResolution(new Resolution("300x300", "300x300", 300, 300))
                .setMinMargins(Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .build();
        verifyLayoutCall(inOrder, adapter, fifthOldAttributes, fifthNewAttributes, true);

        // When print is pressed we ask for a layout which is *not* for preview.
        verifyLayoutCall(inOrder, adapter, fifthNewAttributes, fifthNewAttributes, false);

        // When print is pressed we ask for all selected pages but we got
        // them when asking for the ones for a preview, and the adapter does
        // not report a content change. Hence, there is nothing to write.

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testPrintOptionsChangeAndPrinterChange() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        final PrintAttributes[] printAttributes = new PrintAttributes[1];

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                printAttributes[0] = (PrintAttributes) invocation.getArguments()[1];
                LayoutResultCallback callback = (LayoutResultCallback)
                        invocation.getArguments()[3];
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(PRINT_JOB_NAME)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build();
                callback.onLayoutFinished(info, false);
                // Mark layout was called.
                onLayoutCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                PageRange[] pages = (PageRange[]) args[0];
                ParcelFileDescriptor fd = (ParcelFileDescriptor) args[1];
                WriteResultCallback callback = (WriteResultCallback) args[3];
                writeBlankPages(printAttributes[0], fd, pages[0].getStart(), pages[0].getEnd());
                fd.close();
                callback.onWriteFinished(pages);
                // Mark write was called.
                onWriteCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for write.
        waitForWriteAdapterCallback();

        // Open the print options.
        openPrintOptions();

        // Select the second printer.
        selectPrinter("Second printer");

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(2);

        // Change the color.
        changeColor("Black & White");

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(3);

        // Change the printer to one which supports the current media size.
        // Select the second printer.
        selectPrinter("First printer");

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(4);

        // Click the print button.
        clickPrintButton();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // We always ask for the the first fifty pages for preview.
        PageRange[] firstPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(firstPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // We changed the printer and the new printer does not support the
        // selected media size in which case the default media size of the
        // printer is used resulting in a layout pass. Same for margins.
        PrintAttributes secondOldAttributes = firstNewAttributes;
        PrintAttributes secondNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.ISO_A3)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(new Margins(0, 0, 0, 0))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, secondOldAttributes, secondNewAttributes, true);

        // We changed the color which results in a layout pass.
        PrintAttributes thirdOldAttributes = secondNewAttributes;
        PrintAttributes thirdNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.ISO_A3)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(new Margins(0, 0, 0, 0))
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .build();
        verifyLayoutCall(inOrder, adapter, thirdOldAttributes, thirdNewAttributes, true);

        // We changed the printer to one that does not support the current
        // media size in which case we pick the default media size for the
        // new printer which results in a layout pass. Same for color.
        PrintAttributes fourthOldAttributes = thirdNewAttributes;
        PrintAttributes fourthNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.ISO_A4)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(new Margins(200, 200, 200, 200))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, fourthOldAttributes, fourthNewAttributes, true);

        // When print is pressed we ask for a layout which is *not* for preview.
        verifyLayoutCall(inOrder, adapter, fourthNewAttributes, fourthNewAttributes, false);

        // When print is pressed we ask for all selected pages but we got
        // them when asking for the ones for a preview, and the adapter does
        // not report a content change. Hence, there is nothing to write.

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testPrintOptionsChangeAndNoPrinterChangeAndContentChange()
            throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        final PrintAttributes[] printAttributes = new PrintAttributes[1];

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                printAttributes[0] = (PrintAttributes) invocation.getArguments()[1];
                LayoutResultCallback callback = (LayoutResultCallback) invocation.getArguments()[3];
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(PRINT_JOB_NAME)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build();
                // The content changes after every layout.
                callback.onLayoutFinished(info, true);
                // Mark layout was called.
                onLayoutCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                PageRange[] pages = (PageRange[]) args[0];
                ParcelFileDescriptor fd = (ParcelFileDescriptor) args[1];
                WriteResultCallback callback = (WriteResultCallback) args[3];
                writeBlankPages(printAttributes[0], fd, pages[0].getStart(), pages[0].getEnd());
                fd.close();
                callback.onWriteFinished(pages);
                // Mark write was called.
                onWriteCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for write.
        waitForWriteAdapterCallback();

        // Open the print options.
        openPrintOptions();

        // Select the second printer.
        selectPrinter("Second printer");

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(2);

        // Click the print button.
        clickPrintButton();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // We always ask for the the first fifty pages for preview.
        PageRange[] firstPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(firstPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // We selected the second printer which does not support the media
        // size that was selected, so a new layout happens as the size changed.
        PrintAttributes secondOldAttributes = firstNewAttributes;
        PrintAttributes secondNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.ISO_A3)
                .setResolution(new Resolution("300x300", "300x300", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, secondOldAttributes, secondNewAttributes, true);

        // In the layout callback we reported that the content changed,
        // so the previously written page has to be written again.
        PageRange[] secondPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(secondPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // When print is pressed we ask for a layout which is *not* for preview.
        verifyLayoutCall(inOrder, adapter, secondNewAttributes, secondNewAttributes, false);

        // When print is pressed we ask for all selected pages as the adapter
        // reports that the content changes after every layout pass.
        PageRange[] thirdPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(thirdPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testNewPrinterSupportsSelectedPrintOptions() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        final PrintAttributes[] printAttributes = new PrintAttributes[1];

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                printAttributes[0] = (PrintAttributes) invocation.getArguments()[1];
                LayoutResultCallback callback = (LayoutResultCallback) invocation.getArguments()[3];
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(PRINT_JOB_NAME)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build();
                // The content changes after every layout.
                callback.onLayoutFinished(info, true);
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                PageRange[] pages = (PageRange[]) args[0];
                ParcelFileDescriptor fd = (ParcelFileDescriptor) args[1];
                WriteResultCallback callback = (WriteResultCallback) args[3];
                writeBlankPages(printAttributes[0], fd, pages[0].getStart(), pages[0].getEnd());
                fd.close();
                callback.onWriteFinished(pages);
                // Mark write was called.
                onWriteCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for write.
        waitForWriteAdapterCallback();

        // Open the print options.
        openPrintOptions();

        // Select the third printer.
        selectPrinter("Third printer");

        // Click the print button.
        clickPrintButton();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // We always ask for the the first fifty pages for preview.
        PageRange[] firstPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(firstPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // When print is pressed we ask for a layout which is *not* for preview.
        verifyLayoutCall(inOrder, adapter, firstNewAttributes, firstNewAttributes, false);

        // When print is pressed we ask for all selected pages.
        PageRange[] thirdPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(thirdPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testNothingChangesAllPagesWrittenFirstTime() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        final PrintAttributes[] printAttributes = new PrintAttributes[1];

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                printAttributes[0] = (PrintAttributes) invocation.getArguments()[1];
                LayoutResultCallback callback = (LayoutResultCallback) invocation.getArguments()[3];
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(PRINT_JOB_NAME)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(3)
                        .build();
                callback.onLayoutFinished(info, false);
                // Mark layout was called.
                onLayoutCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                ParcelFileDescriptor fd = (ParcelFileDescriptor) args[1];
                WriteResultCallback callback = (WriteResultCallback) args[3];
                PageRange[] pages = (PageRange[]) args[0];
                writeBlankPages(printAttributes[0], fd, pages[0].getStart(), pages[0].getEnd());
                fd.close();
                callback.onWriteFinished(new PageRange[] {PageRange.ALL_PAGES});
                // Mark write was called.
                onWriteCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for write.
        waitForWriteAdapterCallback();

        // Open the print options.
        openPrintOptions();

        // Select the second printer.
        selectPrinter("Second printer");

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(2);

        // Click the print button.
        clickPrintButton();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // We always ask for the the first fifty pages for preview.
        PageRange[] firstPages = new PageRange[] {new PageRange(0, 2)};
        inOrder.verify(adapter).onWrite(eq(firstPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // We selected the second printer which does not support the media
        // size that was selected, so a new layout happens as the size changed.
        PrintAttributes secondOldAttributes = firstNewAttributes;
        PrintAttributes secondNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.ISO_A3)
                .setResolution(new Resolution("300x300", "300x300", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, secondOldAttributes, secondNewAttributes, true);

        // In the layout callback we reported that the content didn't change,
        // and we wrote all pages in the write call while being asked only
        // for the first page. Hence, all pages were written and they didn't
        // change, therefore no subsequent write call should happen.

        // When print is pressed we ask for a layout which is *not* for preview.
        verifyLayoutCall(inOrder, adapter, secondNewAttributes, secondNewAttributes, false);

        // In the layout callback we reported that the content didn't change,
        // and we wrote all pages in the write call while being asked only
        // for the first page. Hence, all pages were written and they didn't
        // change, therefore no subsequent write call should happen.

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testCancelLongRunningLayout() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                CancellationSignal cancellation = (CancellationSignal) invocation.getArguments()[2];
                final LayoutResultCallback callback = (LayoutResultCallback) invocation
                        .getArguments()[3];
                cancellation.setOnCancelListener(new OnCancelListener() {
                    @Override
                    public void onCancel() {
                        onCancelOperationCalled();
                        callback.onLayoutCancelled();
                    }
                });
                onLayoutCalled();
                return null;
            }
        }, null, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(1);

        // Cancel printing.
        getUiDevice().pressBack(); // wakes up the device.
        getUiDevice().pressBack();

        // Wait for the cancellation request.
        waitForCancelOperationCallbackCalled();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testCancelLongRunningWrite() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                LayoutResultCallback callback = (LayoutResultCallback) invocation.getArguments()[3];
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(PRINT_JOB_NAME)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1)
                        .build();
                callback.onLayoutFinished(info, false);
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                final ParcelFileDescriptor fd = (ParcelFileDescriptor) args[1];
                final CancellationSignal cancellation = (CancellationSignal) args[2];
                final WriteResultCallback callback = (WriteResultCallback) args[3];
                cancellation.setOnCancelListener(new OnCancelListener() {
                    @Override
                    public void onCancel() {
                        try {
                            fd.close();
                        } catch (IOException ioe) {
                            /* ignore */
                        }
                        onCancelOperationCalled();
                        callback.onWriteCancelled();
                    }
                });
                // Mark write was called.
                onWriteCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for write.
        waitForWriteAdapterCallback();

        // Cancel printing.
        getUiDevice().pressBack(); // wakes up the device.
        getUiDevice().pressBack();

        // Wait for the cancellation request.
        waitForCancelOperationCallbackCalled();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // We always ask for the first page for preview.
        PageRange[] firstPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(firstPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testFailedLayout() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                LayoutResultCallback callback = (LayoutResultCallback) invocation.getArguments()[3];
                callback.onLayoutFailed(null);
                // Mark layout was called.
                onLayoutCalled();
                return null;
            }
        }, null, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(1);

        // Cancel printing.
        getUiDevice().pressBack(); // wakes up the device.
        getUiDevice().pressBack();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // No write as layout failed.

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testFailedWrite() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                LayoutResultCallback callback = (LayoutResultCallback) invocation.getArguments()[3];
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(PRINT_JOB_NAME)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1)
                        .build();
                callback.onLayoutFinished(info, false);
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                ParcelFileDescriptor fd = (ParcelFileDescriptor) args[1];
                WriteResultCallback callback = (WriteResultCallback) args[3];
                fd.close();
                callback.onWriteFailed(null);
                // Mark write was called.
                onWriteCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for write.
        waitForWriteAdapterCallback();

        // Cancel printing.
        getUiDevice().pressBack(); // wakes up the device.
        getUiDevice().pressBack();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // We always ask for the first page for preview.
        PageRange[] firstPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(firstPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testRequestedPagesNotWritten() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        final PrintAttributes[] printAttributes = new PrintAttributes[1];

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                printAttributes[0] = (PrintAttributes) invocation.getArguments()[1];
                LayoutResultCallback callback = (LayoutResultCallback) invocation.getArguments()[3];
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(PRINT_JOB_NAME)
                      .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1)
                      .build();
                callback.onLayoutFinished(info, false);
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                ParcelFileDescriptor fd = (ParcelFileDescriptor) args[1];
                WriteResultCallback callback = (WriteResultCallback) args[3];
                PageRange[] pages = (PageRange[]) args[0];
                writeBlankPages(printAttributes[0], fd, Integer.MAX_VALUE, Integer.MAX_VALUE);
                fd.close();
                // Write wrong pages.
                callback.onWriteFinished(new PageRange[] {
                        new PageRange(Integer.MAX_VALUE,Integer.MAX_VALUE)});
                // Mark write was called.
                onWriteCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for write.
        waitForWriteAdapterCallback();

        // Cancel printing.
        getUiDevice().pressBack(); // wakes up the device.
        getUiDevice().pressBack();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // We always ask for the first page for preview.
        PageRange[] firstPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(firstPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testLayoutCallbackNotCalled() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Break the contract and never call the callback.
                // Mark layout called.
                onLayoutCalled();
                return null;
            }
        }, null, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for layout.
        waitForLayoutAdapterCallbackCount(1);

        // Cancel printing.
        getUiDevice().pressBack(); // wakes up the device.
        getUiDevice().pressBack();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    public void testWriteCallbackNotCalled() throws Exception {
        // Configure the print services.
        FirstPrintService.setCallbacks(createFirstMockPrintServiceCallbacks());
        SecondPrintService.setCallbacks(createSecondMockPrintServiceCallbacks());

        // Create a mock print adapter.
        final PrintDocumentAdapter adapter = createMockPrintDocumentAdapter(
            new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                LayoutResultCallback callback = (LayoutResultCallback) invocation.getArguments()[3];
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(PRINT_JOB_NAME)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1)
                        .build();
                callback.onLayoutFinished(info, false);
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                ParcelFileDescriptor fd = (ParcelFileDescriptor) args[1];
                fd.close();
                // Break the contract and never call the callback.
                // Mark write was called.
                onWriteCalled();
                return null;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                // Mark finish was called.
                onFinishCalled();
                return null;
            }
        });

        // Start printing.
        print(adapter);

        // Wait for write.
        waitForWriteAdapterCallback();

        // Cancel printing.
        getUiDevice().pressBack(); // wakes up the device.
        getUiDevice().pressBack();

        // Wait for a finish.
        waitForAdapterFinishCallbackCalled();

        // Wait for the session to be destroyed to isolate tests.
        waitForPrinterDiscoverySessionDestroyCallbackCalled();

        // Verify the expected calls.
        InOrder inOrder = inOrder(adapter);

        // Start is always called first.
        inOrder.verify(adapter).onStart();

        // Start is always followed by a layout. The PDF printer is selected if
        // there are other printers but none of them was used.
        PrintAttributes firstOldAttributes = new PrintAttributes.Builder().build();
        PrintAttributes firstNewAttributes = new PrintAttributes.Builder()
                .setMediaSize(MediaSize.NA_LETTER)
                .setResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300))
                .setMinMargins(Margins.NO_MARGINS).setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        verifyLayoutCall(inOrder, adapter, firstOldAttributes, firstNewAttributes, true);

        // We always ask for the first page for preview.
        PageRange[] firstPages = new PageRange[] {new PageRange(0, 0)};
        inOrder.verify(adapter).onWrite(eq(firstPages), any(ParcelFileDescriptor.class),
                any(CancellationSignal.class), any(WriteResultCallback.class));

        // Finish is always called last.
        inOrder.verify(adapter).onFinish();

        // No other call are expected.
        verifyNoMoreInteractions(adapter);
    }

    private PrintServiceCallbacks createFirstMockPrintServiceCallbacks() {
        final PrinterDiscoverySessionCallbacks callbacks =
                createMockPrinterDiscoverySessionCallbacks(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) {
                PrinterDiscoverySessionCallbacks mock = (PrinterDiscoverySessionCallbacks)
                        invocation.getMock();

                StubbablePrinterDiscoverySession session = mock.getSession();
                PrintService service = session.getService();

                if (session.getPrinters().isEmpty()) {
                    List<PrinterInfo> printers = new ArrayList<PrinterInfo>();

                    // Add the first printer.
                    PrinterId firstPrinterId = service.generatePrinterId("first_printer");
                    PrinterCapabilitiesInfo firstCapabilities =
                            new PrinterCapabilitiesInfo.Builder(firstPrinterId)
                        .setMinMargins(new Margins(200, 200, 200, 200))
                        .addMediaSize(MediaSize.ISO_A4, true)
                        .addMediaSize(MediaSize.ISO_A5, false)
                        .addResolution(new Resolution("300x300", "300x300", 300, 300), true)
                        .setColorModes(PrintAttributes.COLOR_MODE_COLOR,
                                PrintAttributes.COLOR_MODE_COLOR)
                        .build();
                    PrinterInfo firstPrinter = new PrinterInfo.Builder(firstPrinterId,
                            "First printer", PrinterInfo.STATUS_IDLE)
                        .setCapabilities(firstCapabilities)
                        .build();
                    printers.add(firstPrinter);

                    // Add the second printer.
                    PrinterId secondPrinterId = service.generatePrinterId("second_printer");
                    PrinterCapabilitiesInfo secondCapabilities =
                            new PrinterCapabilitiesInfo.Builder(secondPrinterId)
                        .addMediaSize(MediaSize.ISO_A3, true)
                        .addMediaSize(MediaSize.ISO_A4, false)
                        .addResolution(new Resolution("200x200", "200x200", 200, 200), true)
                        .addResolution(new Resolution("300x300", "300x300", 300, 300), false)
                        .setColorModes(PrintAttributes.COLOR_MODE_COLOR
                                | PrintAttributes.COLOR_MODE_MONOCHROME,
                                PrintAttributes.COLOR_MODE_MONOCHROME)
                        .build();
                    PrinterInfo secondPrinter = new PrinterInfo.Builder(secondPrinterId,
                            "Second printer", PrinterInfo.STATUS_IDLE)
                        .setCapabilities(secondCapabilities)
                        .build();
                    printers.add(secondPrinter);

                    // Add the third printer.
                    PrinterId thirdPrinterId = service.generatePrinterId("third_printer");
                    PrinterCapabilitiesInfo thirdCapabilities =
                            new PrinterCapabilitiesInfo.Builder(thirdPrinterId)
                        .addMediaSize(MediaSize.NA_LETTER, true)
                        .addResolution(new Resolution("300x300", "300x300", 300, 300), true)
                        .setColorModes(PrintAttributes.COLOR_MODE_COLOR,
                                PrintAttributes.COLOR_MODE_COLOR)
                        .build();
                    PrinterInfo thirdPrinter = new PrinterInfo.Builder(thirdPrinterId,
                            "Third printer", PrinterInfo.STATUS_IDLE)
                        .setCapabilities(thirdCapabilities)
                        .build();
                    printers.add(thirdPrinter);

                    session.addPrinters(printers);
                }
                return null;
            }
        }, null, null, null, null, new Answer<Void>() {
                @Override
                public Void answer(InvocationOnMock invocation) throws Throwable {
                    // Take a note onDestroy was called.
                    onPrinterDiscoverySessionDestroyCalled();
                    return null;
                }
            });
        return createMockPrintServiceCallbacks(new Answer<PrinterDiscoverySessionCallbacks>() {
            @Override
            public PrinterDiscoverySessionCallbacks answer(InvocationOnMock invocation) {
                return callbacks;
            }
        }, new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                PrintJob printJob = (PrintJob) invocation.getArguments()[0];
                printJob.complete();
                return null;
            }
        }, null);
    }

    private PrintServiceCallbacks createSecondMockPrintServiceCallbacks() {
        return createMockPrintServiceCallbacks(null, null, null);
    }
}
