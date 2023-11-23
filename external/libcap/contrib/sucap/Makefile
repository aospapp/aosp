all: su

su: su.c
	$(CC) -DPAM_APP_NAME=\"sucap\" -o $@ $< -lpam -lpam_misc -lcap
	# to permit all ambient capabilities, this needs all permitted.
	sudo setcap =p ./su

clean:
	rm -f su su.o *~
