package autotest.common.table;

import autotest.common.ui.DateTimeBox;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Panel;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.user.datepicker.client.CalendarUtil;

import java.util.Date;

public class DatetimeSegmentFilter extends SimpleFilter {
    protected DateTimeBox startDatetimeBox;
    protected DateTimeBox endDatetimeBox;
    protected Panel panel;
    protected Label fromLabel;
    protected Label toLabel;
    private String placeHolderStartDatetime;
    private String placeHolderEndDatetime;

    public DatetimeSegmentFilter() {
        startDatetimeBox = new DateTimeBox();
        endDatetimeBox = new DateTimeBox();
        fromLabel = new Label("From");
        toLabel = new Label("to");

        panel = new HorizontalPanel();
        panel.add(fromLabel);
        panel.add(startDatetimeBox);
        panel.add(toLabel);
        panel.add(endDatetimeBox);

        DateTimeFormat dateTimeFormat = DateTimeFormat.getFormat("yyyy-MM-dd");
        Date placeHolderDate = new Date();
        // We want all entries from today, so advance end date to tomorrow.
        CalendarUtil.addDaysToDate(placeHolderDate, 1);
        placeHolderEndDatetime = dateTimeFormat.format(placeHolderDate) + "T00:00";
        setEndTimeToPlaceHolderValue();

        CalendarUtil.addDaysToDate(placeHolderDate, -7);
        placeHolderStartDatetime = dateTimeFormat.format(placeHolderDate) + "T00:00";
        setStartTimeToPlaceHolderValue();

        addValueChangeHandler(
            new ValueChangeHandler() {
                public void onValueChange(ValueChangeEvent event) {
                    notifyListeners();
                }
            },
            new ValueChangeHandler() {
                public void onValueChange(ValueChangeEvent event) {
                    notifyListeners();
                }
            }
        );
    }

    @Override
    public Widget getWidget() {
        return panel;
    }

    public void setStartTimeToPlaceHolderValue() {
        startDatetimeBox.setValue(placeHolderStartDatetime);
    }

    public void setEndTimeToPlaceHolderValue() {
        endDatetimeBox.setValue(placeHolderEndDatetime);
    }

    public void addValueChangeHandler(ValueChangeHandler<String> startTimeHandler,
                                      ValueChangeHandler<String> endTimeHandler) {
        startDatetimeBox.addValueChangeHandler(startTimeHandler);
        endDatetimeBox.addValueChangeHandler(endTimeHandler);
    }
}
